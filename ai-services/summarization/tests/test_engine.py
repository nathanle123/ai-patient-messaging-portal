import numpy as np

from portal_common.testing import HashingEmbedder
from summarization.engine import Summarizer, select, split_sentences, word_count

MESSAGE = (
    "Hi Doc,\n\n"
    "I've been having sharp stomach pains after meals for about two weeks. "
    "The stomach pains get worse at night and sometimes wake me up. "
    "I also wanted to ask whether my parking validation works at the new building. "
    "I tried antacids but the stomach pains after meals keep coming back.\n\n"
    "Thanks,\n<PERSON>"
)


def test_split_preserves_offsets():
    for s in split_sentences(MESSAGE):
        assert MESSAGE[s.start : s.end] == s.text
    texts = [s.text for s in split_sentences(MESSAGE)]
    assert texts[0] == "Hi Doc,"
    assert texts[-2:] == ["Thanks,", "<PERSON>"]


def test_word_count_ignores_placeholders():
    assert word_count("Thanks, <PERSON>") == 1
    assert word_count("I need a refill now") == 5


def test_summary_is_verbatim_and_skips_greetings():
    s = Summarizer(HashingEmbedder()).summarize(MESSAGE)
    assert len(s.selected) == 2  # four candidate sentences
    for sentence in s.selected:
        assert MESSAGE[sentence.start : sentence.end] == sentence.text
        assert sentence.text in MESSAGE
    assert "Hi Doc" not in s.text and "Thanks" not in s.text
    assert "parking" not in s.text  # the off-topic sentence is least representative
    assert [x.start for x in s.selected] == sorted(x.start for x in s.selected)


def test_two_candidates_give_one_sentence_three_give_two():
    two = "I need a refill of my inhaler please. The pharmacy on Main Street is fine for me."
    three = two + " I also have a question about my last lab results."
    assert len(Summarizer(HashingEmbedder()).summarize(two).selected) == 1
    assert len(Summarizer(HashingEmbedder()).summarize(three).selected) == 2


def test_short_message_returns_single_sentence():
    text = "Can you refill my lisinopril prescription please?"
    s = Summarizer(HashingEmbedder()).summarize(text)
    assert s.text == text
    assert s.candidate_count == 1


def test_only_fragments_falls_back_to_them():
    s = Summarizer(HashingEmbedder()).summarize("Thanks!")
    assert s.text == "Thanks!"


def test_mmr_avoids_near_duplicate_second_pick():
    relevance = np.array([0.9, 0.89, 0.6])
    similarity = np.array([[1.0, 0.99, 0.1], [0.99, 1.0, 0.1], [0.1, 0.1, 1.0]])
    assert select(relevance, similarity, k=2) == [0, 2]
