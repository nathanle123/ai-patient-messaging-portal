"""In-memory Communication store. Lost on restart by design (no database)."""

from __future__ import annotations

import threading
import uuid
from collections import OrderedDict
from datetime import datetime, timezone


class CommunicationStore:
    def __init__(self, capacity: int = 20_000):
        self.capacity = capacity
        self._items: OrderedDict[str, dict] = OrderedDict()
        self._by_identifier: dict[tuple[str, str], str] = {}
        self._lock = threading.Lock()

    def __len__(self) -> int:
        return len(self._items)

    def create(self, resource: dict, if_none_exist: tuple[str, str] | None = None) -> tuple[dict, bool]:
        """Store a new Communication. With ``if_none_exist`` (system, value), an existing match is
        returned instead (FHIR conditional create), which makes the platform's write-back idempotent.

        Returns (resource, created)."""
        with self._lock:
            if if_none_exist is not None and if_none_exist in self._by_identifier:
                return self._items[self._by_identifier[if_none_exist]], False
            stored = dict(resource)
            stored["id"] = uuid.uuid4().hex
            stored["meta"] = {**resource.get("meta", {}), "versionId": "1",
                              "lastUpdated": datetime.now(timezone.utc).isoformat(timespec="milliseconds")}
            self._items[stored["id"]] = stored
            for ident in stored.get("identifier", []):
                if ident.get("system") and ident.get("value"):
                    self._by_identifier[(ident["system"], ident["value"])] = stored["id"]
            while len(self._items) > self.capacity:
                _, evicted = self._items.popitem(last=False)
                for ident in evicted.get("identifier", []):
                    self._by_identifier.pop((ident.get("system"), ident.get("value")), None)
            return stored, True

    def get(self, resource_id: str) -> dict | None:
        return self._items.get(resource_id)

    def search(self, subject: str | None = None, identifier: tuple[str | None, str] | None = None,
               count: int = 50) -> tuple[list[dict], int]:
        """Newest first. Returns (page, total matches)."""
        with self._lock:
            items = list(reversed(self._items.values()))
        if subject is not None:
            items = [c for c in items if c.get("subject", {}).get("reference") == subject]
        if identifier is not None:
            system, value = identifier
            items = [c for c in items if any(i.get("value") == value and (system is None or i.get("system") == system)
                                             for i in c.get("identifier", []))]
        return items[:count], len(items)
