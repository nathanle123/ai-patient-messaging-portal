"""Real DeBERTa-v3 zero-shot tests: pytest -m model routing"""

import pytest

pytestmark = pytest.mark.model


@pytest.fixture(scope="module")
def engine():
    from routing.engine import load_engine

    return load_engine()


@pytest.mark.parametrize(
    "text,category",
    [
        ("Hi, I'm almost out of my metformin. Could you send a refill to my pharmacy?", "medication_refill"),
        ("I need to reschedule my appointment next Tuesday to later in the week.", "scheduling"),
        ("Are my blood test results back yet? I'd like to know my cholesterol numbers.", "test_results"),
        ("I got a bill for my last visit but my insurance should have covered it.", "billing"),
        ("I've had a sore throat and a headache for three days.", "symptoms"),
    ],
)
def test_clear_messages_route_correctly(engine, text, category):
    assert engine.route(text).category == category
