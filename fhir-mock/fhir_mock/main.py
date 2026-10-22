"""Minimal mock FHIR R4 server: Patient (read/search) and Communication (create/read/search).

Patients are built in memory from fake_phi.csv at startup; Communications live in memory and are
lost on restart. There is no database. This is a test double for the platform's HAPI FHIR client,
not a conformant FHIR server: it implements only the interactions the platform uses.

Defence in depth: a Communication whose payload contains the subject's own MRN, phone, email or
birth date is rejected (422). The platform only ever sends redacted text, so this should never fire.
"""

from __future__ import annotations

import logging
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import parse_qs

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .patients import MRN_SYSTEM, load_patients
from .store import CommunicationStore

FHIR_JSON = "application/fhir+json"
EVENT_STATUSES = {"preparation", "in-progress", "not-done", "on-hold", "stopped", "completed",
                  "entered-in-error", "unknown"}
MAX_COUNT = 200

log = logging.getLogger("fhir-mock")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    path = Path(os.environ.get("FAKE_PHI_PATH", "/app/seed/fake_phi.csv"))
    app.state.patients = load_patients(path)
    app.state.communications = CommunicationStore(int(os.environ.get("COMMUNICATION_CAPACITY", "20000")))
    app.state.stats = {"created": 0, "deduplicated": 0, "rejected": 0, "rejected_phi": 0}
    log.info("loaded %d Patient resources", len(app.state.patients))
    yield


app = FastAPI(title="Mock FHIR R4 server", version="0.5.0", lifespan=lifespan,
              description="Patient and Communication only, in memory, from synthetic identities. "
                          "Educational test double; not a conformant FHIR server.")


@app.middleware("http")
async def log_requests(request: Request, call_next):
    started = time.perf_counter()
    response = await call_next(request)
    # Path only: query strings carry identifiers (MRNs) and bodies carry message text.
    log.info("%s %s %d %.0fms", request.method, request.url.path, response.status_code,
             (time.perf_counter() - started) * 1000)
    return response


def fhir(body: dict, status: int = 200, headers: dict | None = None) -> JSONResponse:
    return JSONResponse(body, status_code=status, media_type=FHIR_JSON, headers=headers)


def outcome(status: int, code: str, diagnostics: str) -> JSONResponse:
    return fhir({"resourceType": "OperationOutcome",
                 "issue": [{"severity": "error", "code": code, "diagnostics": diagnostics}]}, status)


def base_url(request: Request) -> str:
    return os.environ.get("FHIR_BASE_URL") or str(request.base_url).rstrip("/") + "/fhir"


def bundle(request: Request, resource_type: str, resources: list[dict], total: int) -> JSONResponse:
    base = base_url(request)
    return fhir({
        "resourceType": "Bundle",
        "type": "searchset",
        "total": total,
        "link": [{"relation": "self", "url": f"{base}/{resource_type}"}],
        "entry": [{"fullUrl": f"{base}/{resource_type}/{r['id']}", "resource": r, "search": {"mode": "match"}}
                  for r in resources],
    })


def token(value: str) -> tuple[str | None, str]:
    """FHIR token search parameter: "system|value" or "value"."""
    if "|" in value:
        system, _, code = value.partition("|")
        return (system or None), code
    return None, value


def count(request: Request) -> int:
    try:
        return max(0, min(MAX_COUNT, int(request.query_params.get("_count", "50"))))
    except ValueError:
        return 50


@app.get("/health")
def health(request: Request):
    return {"status": "ok", "patients": len(request.app.state.patients),
            "communications": len(request.app.state.communications), **request.app.state.stats}


@app.get("/fhir/metadata")
def metadata():
    interactions = {"Patient": ["read", "search-type"], "Communication": ["create", "read", "search-type"]}
    return fhir({
        "resourceType": "CapabilityStatement", "status": "active", "date": "2026-10-20", "kind": "instance",
        "fhirVersion": "4.0.1", "format": ["json"],
        "software": {"name": "cmpe272 mock FHIR server", "version": "0.5.0"},
        "rest": [{"mode": "server", "resource": [
            {"type": t, "interaction": [{"code": c} for c in codes],
             "conditionalCreate": t == "Communication"} for t, codes in interactions.items()]}],
    })


# ---------- Patient ----------------------------------------------------------------------------

@app.get("/fhir/Patient")
def search_patients(request: Request):
    patients = list(request.app.state.patients.values())
    if "_id" in request.query_params:
        patients = [p for p in patients if p["id"] == request.query_params["_id"]]
    if "identifier" in request.query_params:
        system, value = token(request.query_params["identifier"])
        patients = [p for p in patients if any(i["value"] == value and (system is None or i["system"] == system)
                                               for i in p["identifier"])]
    return bundle(request, "Patient", patients[:count(request)], len(patients))


@app.get("/fhir/Patient/{patient_id}")
def read_patient(patient_id: str, request: Request):
    patient = request.app.state.patients.get(patient_id)
    return fhir(patient) if patient else outcome(404, "not-found", f"Patient/{patient_id} is not known")


# ---------- Communication ----------------------------------------------------------------------

def leaked_identifiers(patient: dict, text: str) -> list[str]:
    """Which of the subject's own identifiers appear verbatim in the payload (names are not checked)."""
    found = []
    if any(i["value"] in text for i in patient["identifier"]):
        found.append("mrn")
    for t in patient["telecom"]:
        if t["value"] in text:
            found.append(t["system"])
    if patient["birthDate"] in text:
        found.append("birthDate")
    return found


def validate(resource: dict, patients: dict) -> tuple[str, int, str] | None:
    """Return (code, http status, diagnostics) if the Communication is not acceptable."""
    if not isinstance(resource, dict) or resource.get("resourceType") != "Communication":
        return "invalid", 400, "body must be a Communication resource"
    if resource.get("status") not in EVENT_STATUSES:
        return "invalid", 422, "Communication.status is required and must be an event status"
    reference = resource.get("subject", {}).get("reference", "")
    if not reference.startswith("Patient/") or reference.split("/", 1)[1] not in patients:
        return "invalid", 422, "Communication.subject must reference a known Patient"
    payload = resource.get("payload") or []
    if not payload or any(set(p) != {"contentString"} or not isinstance(p["contentString"], str) for p in payload):
        return "invalid", 422, "Communication.payload must be text (contentString) only"
    patient = patients[reference.split("/", 1)[1]]
    if any(leaked_identifiers(patient, p["contentString"]) for p in payload):
        return "security", 422, "payload contains the subject's identifiers; only redacted text is accepted"
    return None


def if_none_exist(header: str | None) -> tuple[str, str] | None:
    """Conditional create: ``If-None-Exist: identifier=system|value`` (an optional "Communication?" prefix)."""
    if not header:
        return None
    query = parse_qs(header.split("?", 1)[-1])
    if "identifier" not in query:
        return None
    system, value = token(query["identifier"][0])
    return (system or "", value)


@app.post("/fhir/Communication")
async def create_communication(request: Request):
    state = request.app.state
    try:
        resource = await request.json()
    except ValueError:
        state.stats["rejected"] += 1
        return outcome(400, "invalid", "body is not JSON")
    problem = validate(resource, state.patients)
    if problem:
        code, status, diagnostics = problem
        state.stats["rejected_phi" if code == "security" else "rejected"] += 1
        log.warning("Communication rejected: %s", code)
        return outcome(status, code, diagnostics)

    stored, created = state.communications.create(resource, if_none_exist(request.headers.get("If-None-Exist")))
    state.stats["created" if created else "deduplicated"] += 1
    location = f"{base_url(request)}/Communication/{stored['id']}/_history/{stored['meta']['versionId']}"
    return fhir(stored, 201 if created else 200, {"Location": location, "Content-Location": location,
                                                  "ETag": f'W/"{stored["meta"]["versionId"]}"'})


@app.get("/fhir/Communication")
def search_communications(request: Request):
    params = request.query_params
    subject = params.get("subject")
    if "patient" in params:
        patient = params["patient"]
        subject = patient if patient.startswith("Patient/") else f"Patient/{patient}"
    identifier = token(params["identifier"]) if "identifier" in params else None
    items, total = request.app.state.communications.search(subject, identifier, count(request))
    return bundle(request, "Communication", items, total)


@app.get("/fhir/Communication/{communication_id}")
def read_communication(communication_id: str, request: Request):
    item = request.app.state.communications.get(communication_id)
    return fhir(item) if item else outcome(404, "not-found", f"Communication/{communication_id} is not known")


__all__ = ["app", "MRN_SYSTEM"]
