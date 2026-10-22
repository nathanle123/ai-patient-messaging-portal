"""pgvector store for message embeddings.

The service connects as the ``search_svc`` database role, which can read and write
``message_embedding`` and nothing else (no access to ``message`` or ``message_original``).
"""

from __future__ import annotations

import threading
from typing import Protocol
from uuid import UUID

import numpy as np


class MessageNotFound(Exception):
    """The message id has no row in ``message`` (foreign-key violation)."""


class VectorStore(Protocol):
    def upsert(self, message_id: UUID, embedding: np.ndarray, model_version: str) -> None: ...

    def nearest(self, embedding: np.ndarray, k: int, exclude: UUID | None = None) -> list[tuple[UUID, float]]: ...

    def ping(self) -> bool: ...


class PgVectorStore:
    def __init__(self, dsn: str):
        self._dsn = dsn
        self._lock = threading.Lock()
        self._conn = None

    def _connection(self):
        import psycopg
        from pgvector.psycopg import register_vector

        if self._conn is None or self._conn.closed:
            self._conn = psycopg.connect(self._dsn, autocommit=True, connect_timeout=5)
            register_vector(self._conn)
        return self._conn

    def _execute(self, sql: str, params: tuple):
        import psycopg

        with self._lock:
            try:
                return self._connection().execute(sql, params)
            except psycopg.errors.ForeignKeyViolation as e:
                raise MessageNotFound() from e
            except psycopg.OperationalError:
                # Stale connection (database restarted): reconnect once.
                self._conn = None
                return self._connection().execute(sql, params)

    def upsert(self, message_id: UUID, embedding: np.ndarray, model_version: str) -> None:
        self._execute(
            """
            INSERT INTO message_embedding (message_id, embedding, model_version)
            VALUES (%s, %s, %s)
            ON CONFLICT (message_id) DO UPDATE
                SET embedding = EXCLUDED.embedding, model_version = EXCLUDED.model_version
            """,
            (message_id, embedding, model_version),
        )

    def nearest(self, embedding: np.ndarray, k: int, exclude: UUID | None = None) -> list[tuple[UUID, float]]:
        # <=> is cosine distance; the HNSW index (vector_cosine_ops) serves this ORDER BY.
        rows = self._execute(
            """
            SELECT message_id, 1 - (embedding <=> %s) AS score
            FROM message_embedding
            WHERE %s::uuid IS NULL OR message_id <> %s::uuid
            ORDER BY embedding <=> %s
            LIMIT %s
            """,
            (embedding, exclude, exclude, embedding, k),
        ).fetchall()
        return [(row[0], float(row[1])) for row in rows]

    def ping(self) -> bool:
        try:
            self._execute("SELECT 1 FROM message_embedding LIMIT 1", ())
            return True
        except Exception:
            return False

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()


class InMemoryStore:
    """Test double with the same semantics as PgVectorStore."""

    def __init__(self, known_message_ids: set[UUID] | None = None):
        self.rows: dict[UUID, np.ndarray] = {}
        self._known = known_message_ids

    def upsert(self, message_id: UUID, embedding: np.ndarray, model_version: str) -> None:
        if self._known is not None and message_id not in self._known:
            raise MessageNotFound()
        self.rows[message_id] = embedding

    def nearest(self, embedding: np.ndarray, k: int, exclude: UUID | None = None) -> list[tuple[UUID, float]]:
        scored = [(mid, float(vec @ embedding)) for mid, vec in self.rows.items() if mid != exclude]
        return sorted(scored, key=lambda x: -x[1])[:k]

    def ping(self) -> bool:
        return True
