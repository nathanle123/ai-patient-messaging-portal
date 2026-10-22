"""Build FHIR R4 Patient resources from fake_phi.csv (synthetic identities only)."""

from __future__ import annotations

import csv
import re
from pathlib import Path

MRN_SYSTEM = "urn:cmpe272:mrn"
SUFFIXES = {"jr", "jr.", "sr", "sr.", "ii", "iii", "iv"}
# "1420 Alder Ln, San Jose, CA 95112"
ADDRESS = re.compile(r"^(?P<line>.+?),\s*(?P<city>[^,]+),\s*(?P<state>[A-Z]{2})\s+(?P<zip>\d{5})$")


def patient_id(mrn: str) -> str:
    """Stable, readable logical id: MRN-482913 -> pat-482913 (same id across restarts)."""
    return "pat-" + re.sub(r"[^0-9A-Za-z]", "", mrn.split("-", 1)[-1]).lower()


def human_name(full: str) -> dict:
    parts = full.split()
    suffix = [p for p in parts if p.lower() in SUFFIXES]
    names = [p for p in parts if p.lower() not in SUFFIXES]
    name = {"use": "official", "text": full, "family": names[-1], "given": names[:-1]}
    if suffix:
        name["suffix"] = suffix
    return name


def address(raw: str) -> dict:
    m = ADDRESS.match(raw.strip())
    if not m:
        return {"use": "home", "text": raw}
    return {"use": "home", "text": raw, "line": [m["line"]], "city": m["city"], "state": m["state"],
            "postalCode": m["zip"], "country": "US"}


def to_patient(row: dict) -> dict:
    return {
        "resourceType": "Patient",
        "id": patient_id(row["mrn"]),
        "meta": {"versionId": "1", "tag": [{"system": "urn:cmpe272:data", "code": "synthetic",
                                            "display": "Synthetic identity (fake_phi.csv)"}]},
        "identifier": [{
            "use": "usual",
            "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0203", "code": "MR",
                                 "display": "Medical record number"}]},
            "system": MRN_SYSTEM,
            "value": row["mrn"],
        }],
        "active": True,
        "name": [human_name(row["name"])],
        "telecom": [{"system": "phone", "value": row["phone"], "use": "home"},
                    {"system": "email", "value": row["email"]}],
        "birthDate": row["dob"],
        "address": [address(row["address"])],
    }


def load_patients(path: Path) -> dict[str, dict]:
    with path.open(newline="") as f:
        rows = list(csv.DictReader(f))
    patients = {}
    for row in rows:
        p = to_patient(row)
        if p["id"] in patients:
            raise ValueError(f"duplicate MRN-derived id {p['id']}")
        patients[p["id"]] = p
    return patients
