import math
import threading
from uuid import UUID

from app.rag.vector_store.base import ChunkRecord, SearchHit, VectorStore


class InMemoryVectorStore(VectorStore):
    """Almacén en memoria con búsqueda exacta por coseno. Para tests y desarrollo local:
    no persiste nada entre reinicios."""

    name = "memory"

    def __init__(self) -> None:
        self._chunks: dict[UUID, tuple[UUID, str, list[ChunkRecord]]] = {}
        self._lock = threading.Lock()

    def replace_document(
        self, organization_id: UUID, document_id: UUID, chunks: list[ChunkRecord], embedding_model: str
    ) -> None:
        with self._lock:
            self._chunks[document_id] = (organization_id, embedding_model, list(chunks))

    def delete_document(self, organization_id: UUID, document_id: UUID) -> int:
        with self._lock:
            entry = self._chunks.get(document_id)
            if entry is None or entry[0] != organization_id:
                return 0
            del self._chunks[document_id]
            return len(entry[2])

    def search(
        self, organization_id: UUID, embedding: list[float], embedding_model: str, limit: int
    ) -> list[SearchHit]:
        with self._lock:
            candidates = [
                chunk
                for org, model, chunks in self._chunks.values()
                if org == organization_id and model == embedding_model
                for chunk in chunks
            ]
        hits = [
            SearchHit(c.document_id, c.title, c.document_type, c.chunk_index, c.content,
                      _cosine(embedding, c.embedding))
            for c in candidates
        ]
        hits.sort(key=lambda hit: hit.score, reverse=True)
        return hits[:limit]

    def is_available(self) -> bool:
        return True

    def chunk_count(self, document_id: UUID) -> int:
        entry = self._chunks.get(document_id)
        return len(entry[2]) if entry else 0


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    norm = math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b))
    return dot / norm if norm else 0.0
