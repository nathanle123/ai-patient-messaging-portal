from dataclasses import dataclass

from phi_detection.engine import drop_false_positives


@dataclass
class R:
    entity_type: str
    start: int
    end: int
    score: float = 0.85


def test_salutation_person_false_positives_are_dropped():
    text = "Hi Doc, this is Maria."
    results = [R("PERSON", 3, 6), R("PERSON", 16, 21)]
    kept = drop_false_positives(text, results)
    assert [(text[r.start : r.end]) for r in kept] == ["Maria"]


def test_non_person_entities_are_untouched():
    text = "Doc 408-555-0142"
    results = [R("PHONE_NUMBER", 4, 16)]
    assert drop_false_positives(text, results) == results
