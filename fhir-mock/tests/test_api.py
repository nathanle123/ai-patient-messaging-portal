import pytest
from fastapi.testclient import TestClient

from fhir_mock.patients import MRN_SYSTEM, address, human_name, patient_id

CSV = """name,dob,phone,email,mrn,address
Maria Lopez,1962-03-14,(408) 555-0142,maria.lopez@example.com,MRN-482913,"1420 Alder Ln, San Jose, CA 95112"
Anthony J. Russo Jr.,1946-11-24,(510) 555-0114,ajrusso@example.com,MRN-526871,"12 Chestnut Ct, Hayward, CA 94541"
"""
MESSAGE_ID_SYSTEM = "urn:ietf:rfc:3986"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    path = tmp_path / "fake_phi.csv"
    path.write_text(CSV)
    monkeypatch.setenv("FAKE_PHI_PATH", str(path))
    from fhir_mock.main import app

    with TestClient(app) as c:
        yield c


def communication(text="Hi, this is <PERSON>. I need a refill.", subject="Patient/pat-482913", message_id="m-1"):
    return {
        "resourceType": "Communication",
        "identifier": [{"system": MESSAGE_ID_SYSTEM, "value": f"urn:uuid:{message_id}"}],
        "status": "completed",
        "subject": {"reference": subject},
        "payload": [{"contentString": text}],
    }


def test_patients_are_built_from_csv(client):
    bundle = client.get("/fhir/Patient").json()
    assert bundle["resourceType"] == "Bundle" and bundle["total"] == 2
    maria = client.get("/fhir/Patient/pat-482913").json()
    assert maria["identifier"][0] == {**maria["identifier"][0], "system": MRN_SYSTEM, "value": "MRN-482913"}
    assert maria["name"][0]["family"] == "Lopez" and maria["birthDate"] == "1962-03-14"
    assert maria["address"][0]["city"] == "San Jose" and maria["address"][0]["postalCode"] == "95112"


def test_patient_search_by_identifier_token(client):
    hit = client.get("/fhir/Patient", params={"identifier": f"{MRN_SYSTEM}|MRN-526871"}).json()
    assert [e["resource"]["id"] for e in hit["entry"]] == ["pat-526871"]
    assert client.get("/fhir/Patient", params={"identifier": "MRN-526871"}).json()["total"] == 1
    assert client.get("/fhir/Patient", params={"identifier": "other|MRN-526871"}).json()["total"] == 0


def test_unknown_patient_is_operation_outcome_404(client):
    res = client.get("/fhir/Patient/pat-000000")
    assert res.status_code == 404
    assert res.headers["content-type"].startswith("application/fhir+json")
    assert res.json()["resourceType"] == "OperationOutcome"


def test_create_and_read_communication(client):
    res = client.post("/fhir/Communication", json=communication())
    assert res.status_code == 201
    created = res.json()
    assert res.headers["location"].endswith(f"/fhir/Communication/{created['id']}/_history/1")
    assert client.get(f"/fhir/Communication/{created['id']}").json()["payload"][0]["contentString"].startswith("Hi")
    found = client.get("/fhir/Communication", params={"subject": "Patient/pat-482913"}).json()
    assert found["total"] == 1 and found["entry"][0]["resource"]["id"] == created["id"]


def test_conditional_create_is_idempotent(client):
    header = {"If-None-Exist": f"identifier={MESSAGE_ID_SYSTEM}|urn:uuid:m-7"}
    first = client.post("/fhir/Communication", json=communication(message_id="m-7"), headers=header)
    second = client.post("/fhir/Communication", json=communication(message_id="m-7"), headers=header)
    assert (first.status_code, second.status_code) == (201, 200)
    assert first.json()["id"] == second.json()["id"]
    assert client.get("/fhir/Communication", params={"identifier": "urn:uuid:m-7"}).json()["total"] == 1
    assert client.get("/health").json()["deduplicated"] == 1


@pytest.mark.parametrize("text", [
    "My MRN is MRN-482913, please refill.",
    "Call me at (408) 555-0142.",
    "Email maria.lopez@example.com",
    "Born 1962-03-14.",
])
def test_payload_with_subject_identifiers_is_rejected(client, text):
    res = client.post("/fhir/Communication", json=communication(text=text))
    assert res.status_code == 422
    assert res.json()["issue"][0]["code"] == "security"
    assert text not in res.text  # the rejection never echoes the payload
    assert client.get("/health").json()["rejected_phi"] == 1


@pytest.mark.parametrize("mutate, status", [
    (lambda c: c.update(resourceType="Patient"), 400),
    (lambda c: c.update(status="sent"), 422),
    (lambda c: c.update(subject={"reference": "Patient/pat-000000"}), 422),
    (lambda c: c.update(payload=[{"contentAttachment": {"url": "x"}}]), 422),
    (lambda c: c.update(payload=[]), 422),
])
def test_invalid_communications_are_rejected(client, mutate, status):
    body = communication()
    mutate(body)
    assert client.post("/fhir/Communication", json=body).status_code == status


def test_helpers():
    assert patient_id("MRN-089634") == "pat-089634"
    assert human_name("Anthony J. Russo Jr.") == {"use": "official", "text": "Anthony J. Russo Jr.", "family": "Russo",
                                                  "given": ["Anthony", "J."], "suffix": ["Jr."]}
    assert address("somewhere odd") == {"use": "home", "text": "somewhere odd"}
