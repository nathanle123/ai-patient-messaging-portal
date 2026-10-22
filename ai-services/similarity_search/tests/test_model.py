"""Real MiniLM + pgvector test. Needs the compose database: docker compose run --rm similarity-search pytest -m model similarity_search"""

import os
from uuid import uuid4

import pytest

pytestmark = pytest.mark.model


def test_minilm_ranks_paraphrase_above_unrelated():
    from portal_common.embeddings import load_minilm
    from similarity_search.engine import SearchEngine
    from similarity_search.store import InMemoryStore

    engine = SearchEngine(load_minilm(), InMemoryStore())
    refill, bill = uuid4(), uuid4()
    engine.index_and_search(refill, "Could you send a refill of my metformin to the pharmacy?", 5, True)
    engine.index_and_search(bill, "I was charged twice for my last appointment.", 5, True)
    neighbors = engine.search("I'm running out of my diabetes medication", 2)
    assert neighbors[0].message_id == refill
    assert neighbors[0].score > neighbors[1].score


@pytest.mark.skipif(not os.environ.get("SEARCH_DB_DSN"), reason="needs SEARCH_DB_DSN")
def test_search_role_can_use_embeddings_but_not_message_tables():
    import psycopg

    with psycopg.connect(os.environ["SEARCH_DB_DSN"]) as conn:
        conn.execute("SELECT count(*) FROM message_embedding").fetchone()
        for table in ("message", "message_original", "app_user"):
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                conn.execute(f"SELECT 1 FROM {table} LIMIT 1")
            conn.rollback()
