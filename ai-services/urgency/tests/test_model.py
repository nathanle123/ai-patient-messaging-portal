"""Real pipeline tests (spaCy en_core_web_sm + negspaCy): pytest -m model urgency"""

import pytest

pytestmark = pytest.mark.model


@pytest.fixture(scope="module")
def engine():
    from urgency.engine import load_engine

    return load_engine()


@pytest.mark.parametrize(
    "text,label",
    [
        ("I've had crushing chest pain for an hour and I'm short of breath.", "urgent"),
        ("Hi Doc, I have no chest pain and no shortness of breath, just a mild fever since yesterday.", "soon"),
        ("No fever today. Chest pain started this morning.", "urgent"),
        ("I denies any chest pain. Can I get a refill of my lisinopril?", "routine"),
        ("Can I move my appointment with <PERSON> to next Tuesday?", "routine"),
        ("I've been having thoughts that I want to die.", "urgent"),
        ("The rash is spreading and I feel dizzy.", "soon"),
        ("I ran out of my insulin yesterday.", "soon"),
        ("My bill from last month looks wrong.", "routine"),
    ],
)
def test_labels(engine, text, label):
    got, flags = engine.classify(text)
    assert got == label, f"{text!r}: {[(f.phrase, f.tier, f.negated) for f in flags]}"


def test_offsets_point_at_the_phrase(engine):
    text = "Okay so far. But now I have chest pain."
    _, flags = engine.classify(text)
    assert [text[f.start : f.end].lower() for f in flags] == ["chest pain"]


@pytest.mark.parametrize(
    "text,label",
    [
        ("No fever, chills, or vomiting.", "routine"),  # list after one negation: all negated
        ("I don't have chest pain anymore but now I'm short of breath.", "urgent"),
        ("No chest pain, only some dizziness when I stand up.", "soon"),
        ("I'm having some trouble breathing through my nose, it's all stuffed up.", "soon"),
        ("Constant congestion that's making it hard to breathe.", "soon"),
        ("I'm having trouble breathing and my lips look blue.", "urgent"),
    ],
)
def test_negation_scope(engine, text, label):
    got, flags = engine.classify(text)
    assert got == label, f"{text!r}: {[(f.phrase, f.tier, f.negated) for f in flags]}"
