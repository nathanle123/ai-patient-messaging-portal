"""Real-model tests: run inside the image with  pytest -m model phi_detection."""

import pytest

pytestmark = pytest.mark.model

# Synthetic identities from fake_phi.csv.
CASES = [
    (
        "Hi Doc,\n\nThis is Maria Lopez, DOB 1962-03-14. I've had a cough for 3 days and it got worse "
        "yesterday. You can call me at (408) 555-0142 or email maria.lopez@example.com. My MRN is "
        "MRN-482913.\n\nThanks,\nMaria",
        ["Maria Lopez", "1962-03-14", "(408) 555-0142", "maria.lopez@example.com", "MRN-482913"],
        ["cough for 3 days", "yesterday", "Hi Doc"],
    ),
    (
        "Hello, I'm writing for my father Robert Chen (born January 30, 1955). We moved to "
        "47 Dogwood Dr, Fremont, CA 94536 and need his refill sent to the new pharmacy. "
        "His cell is 510-555-0164.",
        ["Robert Chen", "January 30, 1955", "47 Dogwood Dr", "Fremont", "510-555-0164"],
        ["refill", "pharmacy"],
    ),
    (
        "My daughter Olivia Martinez was born 04/30/2004 and her medical record number is 839015. "
        "She has a rash since Monday.",
        ["Olivia Martinez", "04/30/2004", "839015"],
        ["rash since Monday"],
    ),
]


@pytest.fixture(scope="module")
def engine():
    from phi_detection.engine import load_engine

    return load_engine()


@pytest.mark.parametrize("text,phi,kept", CASES)
def test_phi_removed_and_clinical_context_kept(engine, text, phi, kept):
    redacted = engine.redact(text).redacted_text
    for value in phi:
        assert value not in redacted, f"{value!r} leaked: {redacted!r}"
    for value in kept:
        assert value in redacted, f"{value!r} was over-redacted: {redacted!r}"


def test_message_without_phi_is_unchanged(engine):
    text = "I need a refill of my blood pressure medication. I have two pills left."
    assert engine.redact(text).redacted_text == text
