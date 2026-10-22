"""Real MiniLM test: pytest -m model summarization"""

import pytest

pytestmark = pytest.mark.model


def test_real_embeddings_pick_on_topic_verbatim_sentences():
    from summarization.engine import load_engine

    text = (
        "Hi Doc,\n\nI've had a dry cough and a low fever for four days now. "
        "The cough is worse at night and I can't sleep. "
        "By the way, the parking garage was closed when I came in last month. "
        "Should I come in to be seen or try something over the counter first?\n\nThanks,\n<PERSON>"
    )
    s = load_engine().summarize(text)
    assert 1 <= len(s.selected) <= 2
    assert all(x.text in text for x in s.selected)
    assert "parking" not in s.text
