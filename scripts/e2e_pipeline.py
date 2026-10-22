#!/usr/bin/env python3
"""End-to-end checks of the PHI-first Kafka pipeline against a running `docker compose` stack.

  1. happy path: a patient message with synthetic PHI gets all four AI results, and the Kafka record
     carries redacted text only
  2. clinician access: reading the original is audited, review is recorded, a second review is 409
  3. redaction failure: with the PHI service stopped, the message is REDACTION_FAILED and never
     published
  4. dead-letter queues: malformed records land in results.urgency.dlq and messages.redacted.dlq
  5. idempotency: a duplicate result record does not create a second row
  6. analytics: service events persisted, live/technical metrics, source filter, aggregation, demo
     endpoint guards
  7. FHIR R4: the processed message is written back to the mock FHIR server as a Communication with
     redacted text only, linked to the patient's Patient; a FHIR outage does not block intake, and the
     write-back catches up when the server returns; resync is admin-only and audited

Usage: python3 scripts/e2e_pipeline.py   (standard library only; needs docker compose on PATH)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = os.environ.get("BASE_URL", "http://localhost:3000")
FHIR = os.environ.get("FHIR_URL", "http://localhost:8006/fhir")
PHI = ["James Carter", "11/02/1978", "(650) 555-0187", "jcarter78@example.com", "MRN-130577"]
MESSAGE = (
    "Hi Doc,\n\nThis is James Carter, DOB 11/02/1978, MRN-130577. Since yesterday I've had chest pain when "
    "I climb stairs, but no shortness of breath. I'm also almost out of my lisinopril and need a refill. "
    "Please call me at (650) 555-0187 or email jcarter78@example.com.\n\nThanks,\nJames"
)
passed = 0


def env(name: str) -> str:
    if os.environ.get(name):
        return os.environ[name]
    for line in (ROOT / ".env").read_text().splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    sys.exit(f"{name} not set")


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, json.dumps(body).encode() if body is not None else None, headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def fhir_get(path):
    with urllib.request.urlopen(FHIR + path, timeout=10) as r:
        return json.load(r)


def communication_for(message_id):
    query = urllib.parse.quote(f"urn:ietf:rfc:3986|urn:uuid:{message_id}")
    bundle = fhir_get(f"/Communication?identifier={query}")
    return bundle["entry"][0]["resource"] if bundle["total"] == 1 else None


def login(user):
    return call("POST", "/api/auth/login", {"username": user, "password": env("DEMO_USER_PASSWORD")})[1]["token"]


def compose(*args, input_text=None):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, input=input_text, capture_output=True,
                          text=True, check=True).stdout


def psql(sql):
    return compose("exec", "-T", "postgres", "psql", "-At", "-U", env("POSTGRES_USER"), "-d", env("POSTGRES_DB"),
                   "-c", sql).strip()


def topic_records(topic, timeout_ms=4000):
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
         "--bootstrap-server", "localhost:9092", "--topic", topic, "--from-beginning", "--timeout-ms", str(timeout_ms),
         "--property", "print.headers=true"],
        cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace")  # DLQ headers hold binary values
    return out.stdout.splitlines()


def produce(topic, key, value):
    compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "localhost:9092",
            "--topic", topic, "--property", "parse.key=true", "--property", "key.separator=|",
            input_text=f"{key}|{value}\n")


def check(name, ok, detail=""):
    global passed
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""))
    if not ok:
        sys.exit(1)
    passed += 1


def wait_for(fn, timeout=90, every=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(every)
    return None


def main():
    patient, clinician = login("james.carter"), login("dr.alvarez")

    # 1. happy path ----------------------------------------------------------------------------
    status, sent = call("POST", "/api/messages", {"text": MESSAGE}, patient)
    check("patient can submit", status == 201, sent.get("status"))
    mid = sent["id"]
    detail = wait_for(lambda: (d := call("GET", f"/api/clinician/messages/{mid}", token=clinician)[1])
                      and d.get("status") == "PROCESSED" and d)
    check("all four AI results persisted", detail and len(detail["results"]) == 4,
          detail and sorted(detail["results"]))
    check("PHI absent from stored redacted text", not any(v in detail["redactedText"] for v in PHI))
    check("urgency flagged", detail["urgencyLabel"] == "urgent", detail["urgencyLabel"])
    check("routing category set", detail["category"] in ("medication_refill", "symptoms"), detail["category"])
    summary = detail["results"]["summarization"]
    check("summary is verbatim", all(s["text"] in detail["redactedText"] for s in summary["sentences"]))
    records = [r for r in topic_records("messages.redacted") if mid in r]
    check("message on messages.redacted", len(records) >= 1)
    check("Kafka record has no PHI", not any(v in r for r in records for v in PHI))
    events = [json.loads(r.split("\t")[-1]) for r in topic_records("service.events") if mid in r]
    services = {e["service"] for e in events}
    check("service events from PHI, the four AI services and FHIR",
          services >= {"phi-detection", "urgency", "routing", "summarization", "search"}, sorted(services))
    check("service events have the fixed schema",
          all(set(e) == {"event_id", "message_id", "service", "status", "latency_ms", "timestamp"} for e in events))

    # 2. clinician access ----------------------------------------------------------------------
    before = int(psql(f"select count(*) from audit_log where action='VIEW_ORIGINAL_MESSAGE' and target_id='{mid}'"))
    status, original = call("GET", f"/api/clinician/messages/{mid}/original", token=clinician)
    after = int(psql(f"select count(*) from audit_log where action='VIEW_ORIGINAL_MESSAGE' and target_id='{mid}'"))
    check("clinician reads original, and it is audited", status == 200 and "James Carter" in original["originalText"]
          and after == before + 1)
    check("patient cannot read the clinician view",
          call("GET", f"/api/clinician/messages/{mid}", token=patient)[0] == 403)
    status, reviewed = call("POST", f"/api/clinician/messages/{mid}/review", token=clinician)
    check("clinician marks reviewed", status == 200 and reviewed["reviewedAt"] is not None)
    check("second review is a conflict", call("POST", f"/api/clinician/messages/{mid}/review", token=clinician)[0] == 409)
    check("review is audited", psql(f"select count(*) from audit_log where action='REVIEW_MESSAGE' and target_id='{mid}'") == "1")

    # 6. analytics ----------------------------------------------------------------------------------
    admin = login("admin")
    persisted = wait_for(lambda: int(psql(f"select count(distinct service) from service_event where message_id='{mid}' "
                                          f"and service <> 'fhir'")) == 5, timeout=30)
    check("service events persisted for all five services", bool(persisted))
    check("manual submission tagged source=manual", psql(f"select source from message where id='{mid}'") == "manual")
    status, tech = call("GET", "/api/admin/analytics/technical", token=admin)
    names = {x["service"] for x in tech["services"]}
    check("technical metrics cover every service", status == 200 and names >= {"phi-detection", "urgency", "routing",
                                                                                 "summarization", "search"}, sorted(names))
    check("all services healthy", all(x["health"] == "up" for x in tech["services"]),
          {x["service"]: x["health"] for x in tech["services"]})
    check("live latency from Kafka events", tech["services"][0]["live"] is not None)
    s_all = call("GET", "/api/admin/analytics/summary?source=all", token=admin)[1]
    db_urgent = int(psql("select count(*) from message where urgency_label='urgent' and reviewed_at is null"))
    check("unreviewed urgent matches the database", s_all["totals"]["unreviewedUrgent"] == db_urgent,
          f"{s_all['totals']['unreviewedUrgent']} vs {db_urgent}")
    s_manual = call("GET", "/api/admin/analytics/summary?source=manual", token=admin)[1]
    check("source filter narrows counts", s_manual["totals"]["messages"] == int(psql(
        "select count(*) from message where source='manual' and status <> 'RECEIVED'")))
    check("summary contains counts only", "redactedText" not in json.dumps(s_all) and "James" not in json.dumps(s_all))
    status, jobs = call("POST", "/api/admin/analytics/aggregate", token=admin)
    check("manual aggregation runs hourly and daily jobs", status == 200 and [j["job"] for j in jobs] == ["hourly", "daily"])
    status, trends = call("GET", "/api/admin/analytics/trends?granularity=hourly&hours=48", token=admin)
    check("hourly trends return 48 buckets", status == 200 and len(trends["buckets"]) == 48)
    check("clinician cannot read analytics", call("GET", "/api/admin/analytics/summary", token=clinician)[0] == 403)
    old = "2020-01-01T00:00:00Z"
    status, _ = call("POST", "/api/demo/messages", {"text": "x", "source": "synthetic", "submittedAt": old}, patient)
    check("demo endpoint rejects backfill beyond 30 days", status == 400)
    status, _ = call("POST", "/api/demo/messages", {"text": "x", "source": "manual"}, patient)
    check("demo endpoint refuses source=manual", status == 400)

    # 5. idempotency (uses the processed message) ------------------------------------------------
    dup = json.dumps({"message_id": mid, "service": "urgency", "model_version": "dup", "processed_at":
                      "2026-01-01T00:00:00Z", "result": {"label": "routine", "flags": []}})
    produce("results.urgency", mid, dup)
    time.sleep(3)
    check("duplicate result ignored", psql(f"select count(*) from ai_result where message_id='{mid}'") == "4"
          and psql(f"select urgency_label from message where id='{mid}'") == "urgent")

    # 4. dead-letter queues ------------------------------------------------------------------------
    marker = f"e2e-{uuid.uuid4().hex[:8]}"
    produce("results.urgency", marker, json.dumps({"marker": marker}))
    produce("messages.redacted", marker, "not json " + marker)
    dlq = wait_for(lambda: [r for r in topic_records("results.urgency.dlq", 3000) if marker in r], timeout=30)
    check("invalid result dead-lettered to results.urgency.dlq", bool(dlq))
    check("DLQ headers carry no stack trace", dlq and "kafka_dlt-exception-stacktrace" not in dlq[0])
    in_dlq = wait_for(lambda: (rs := [r for r in topic_records("messages.redacted.dlq", 3000) if marker in r])
                      and len(rs) >= 4 and rs, timeout=30)
    workers = sorted({h.split(":")[1].split(",")[0] for r in in_dlq or [] for h in r.split(",") if "dlq.service" in h})
    check("malformed message dead-lettered by all four workers", in_dlq is not None and len(in_dlq) >= 4, workers)

    # 7. FHIR R4 write-back ----------------------------------------------------------------------
    comm = wait_for(lambda: communication_for(mid), timeout=45)
    check("Communication written back for the processed message", bool(comm))
    check("Communication payload is exactly the redacted text",
          comm and comm["payload"] == [{"contentString": detail["redactedText"]}])
    check("Communication carries no PHI from the original", comm and not any(v in json.dumps(comm) for v in PHI))
    mrn = urllib.parse.quote("urn:cmpe272:mrn|MRN-130577")
    fhir_patient = fhir_get(f"/Patient?identifier={mrn}")["entry"][0]["resource"]["id"]
    check("Communication subject is the patient's FHIR Patient (looked up by MRN)",
          comm and comm["subject"]["reference"] == f"Patient/{fhir_patient}", comm and comm["subject"])
    flags = {e["url"].rsplit("/", 1)[-1]: {x["url"]: next(v for k, v in x.items() if k.startswith("value"))
                                            for x in e["extension"]} for e in comm.get("extension", [])} if comm else {}
    check("urgency and routing results carried as extensions",
          flags.get("ai-urgency-flag", {}).get("label") == detail["urgencyLabel"]
          and flags.get("ai-routing", {}).get("category") == detail["category"], flags)
    check("message row records the Communication id",
          psql(f"select fhir_status || '|' || fhir_communication_id from message where id='{mid}'") == f"WRITTEN|{comm['id']}")
    check("clinician detail shows the FHIR link",
          call("GET", f"/api/clinician/messages/{mid}", token=clinician)[1]["fhir"]
          == {"patientId": fhir_patient, "communicationId": comm["id"], "status": "WRITTEN"})
    compose("stop", "fhir-mock")
    try:
        status, sent2 = call("POST", "/api/messages", {"text": "Can I move my appointment on Friday to next week?"}, patient)
        oid = sent2["id"]
        check("intake succeeds while the FHIR server is down", status == 201)
        processed = wait_for(lambda: psql(f"select status from message where id='{oid}'") == "PROCESSED", timeout=120)
        time.sleep(2)
        check("AI processing unaffected; write-back left pending",
              bool(processed) and psql(f"select coalesce(fhir_status, 'pending') from message where id='{oid}'") == "pending")
    finally:
        compose("start", "fhir-mock")
    caught_up = wait_for(lambda: psql(f"select fhir_status from message where id='{oid}'") == "WRITTEN", timeout=90)
    check("write-back catches up once the FHIR server is back", bool(caught_up) and communication_for(oid) is not None)
    status, reset = call("POST", "/api/admin/fhir/resync", token=login("admin"))
    check("admin resync re-queues every Communication (the mock is in-memory)", status == 200 and reset["reset"] > 0, reset)
    check("resync is audited", int(psql("select count(*) from audit_log where action='FHIR_RESYNC'")) >= 1)
    check("clinician cannot resync", call("POST", "/api/admin/fhir/resync", token=clinician)[0] == 403)

    # 3. redaction failure blocks publishing -------------------------------------------------------
    compose("stop", "phi-detection")
    try:
        status, failed = call("POST", "/api/messages", {"text": "Please call Grace Kim at (669) 555-0118."}, patient)
        fid = failed["id"]
        check("submission accepted while PHI service is down", status == 201)
        check("message marked REDACTION_FAILED", psql(f"select status from message where id='{fid}'") == "REDACTION_FAILED")
        check("failed message never published", not [r for r in topic_records("messages.redacted") if fid in r])
        ev = [json.loads(r.split("\t")[-1]) for r in topic_records("service.events") if fid in r]
        check("failure emitted a service event", [e["status"] for e in ev] in (["FAILED"], ["TIMEOUT"]), ev and ev[0]["status"])
        check("failed message visible to clinician", call("GET", f"/api/clinician/messages/{fid}", token=clinician)[1]["status"]
              == "REDACTION_FAILED")
    finally:
        compose("start", "phi-detection")
    print(f"\n{passed} checks passed")


if __name__ == "__main__":
    main()
