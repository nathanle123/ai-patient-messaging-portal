from urgency.rules import SOON, URGENT, Flag, decide, ruler_patterns


def flag(tier, negated=False):
    return Flag(phrase="x", tier=tier, negated=negated, start=0, end=1)


def test_urgent_beats_soon():
    assert decide([flag(SOON), flag(URGENT)]) == "urgent"


def test_negated_urgent_falls_back_to_soon_or_routine():
    assert decide([flag(URGENT, negated=True), flag(SOON)]) == "soon"
    assert decide([flag(URGENT, negated=True)]) == "routine"


def test_no_flags_is_routine():
    assert decide([]) == "routine"


def test_patterns_are_unique_and_longest_first():
    patterns = ruler_patterns()
    phrases = [p["pattern"] for p in patterns]
    assert len(phrases) == len(set(phrases))
    assert [len(p) for p in phrases] == sorted((len(p) for p in phrases), reverse=True)
    assert all(p == p.lower() for p in phrases)


def test_breathing_flag_with_nasal_cue_is_downgraded():
    from urgency.rules import contextual_tier

    assert contextual_tier("trouble breathing", URGENT, "Trouble breathing through my nose, all stuffed up.") == (
        SOON, "downgraded: nasal context")
    assert contextual_tier("trouble breathing", URGENT, "Trouble breathing and my chest hurts.") == (URGENT, None)
    assert contextual_tier("chest pain", URGENT, "Chest pain and a stuffy nose.") == (URGENT, None)
