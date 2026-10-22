"""Pattern-level tests: no spaCy model needed. Scores here are base scores before context boosting."""

from phi_detection.engine import SCORE_THRESHOLD
from phi_detection.recognizers import (
    dob_recognizer,
    email_recognizer,
    mrn_recognizer,
    phone_recognizer,
    street_address_recognizer,
)


def found(recognizer, text):
    results = recognizer.analyze(text=text, entities=recognizer.supported_entities, nlp_artifacts=None)
    return [(text[r.start : r.end], r.score) for r in results]


def confident(recognizer, text):
    return [value for value, score in found(recognizer, text) if score >= SCORE_THRESHOLD]


def test_phone_formats():
    r = phone_recognizer()
    assert confident(r, "Call me at (408) 555-0142 tomorrow") == ["(408) 555-0142"]
    assert confident(r, "my cell is 650-555-0187.") == ["650-555-0187"]
    assert confident(r, "or +1 415 555 0129") == ["+1 415 555 0129"]
    # Space-separated digits are below threshold until context boosts them.
    assert confident(r, "lot 408 555 0142") == []


def test_email():
    assert confident(email_recognizer(), "write to maria.lopez@example.com, thanks") == ["maria.lopez@example.com"]


def test_mrn_prefixed_is_confident_bare_digits_need_context():
    r = mrn_recognizer()
    assert confident(r, "My MRN is MRN-482913.") == ["MRN-482913"]
    assert confident(r, "mrn: 482913") == ["mrn: 482913"]
    assert confident(r, "I took 500000 steps") == []


def test_dates_need_birth_context():
    r = dob_recognizer()
    for text, date in [
        ("born 1962-03-14", "1962-03-14"),
        ("dob 3/14/1962", "3/14/1962"),
        ("on March 14, 1962", "March 14, 1962"),
        ("on 14th of March 1962", "14th of March 1962"),
    ]:
        assert [v for v, _ in found(r, text)] == [date]
        assert confident(r, text) == []  # below threshold without the analyzer's context boost


def test_street_address_with_city_state_zip():
    r = street_address_recognizer()
    assert confident(r, "I moved to 1420 Alder Ln, San Jose, CA 95112 last month") == [
        "1420 Alder Ln, San Jose, CA 95112"
    ]
    assert confident(r, "Send it to 26 Oak Hollow Dr please") == ["26 Oak Hollow Dr"]
    assert confident(r, "64 Willow Bend, Redwood City, CA 94061") == ["64 Willow Bend, Redwood City, CA 94061"]


def test_street_address_ignores_lowercase_prose():
    r = street_address_recognizer()
    assert confident(r, "I walked 2 blocks down the road") == []
    assert confident(r, "take 2 tablets each day and rest") == []
