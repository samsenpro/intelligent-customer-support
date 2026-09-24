from dataclasses import dataclass

from app.core.text import content_stems
from app.rag.vector_store.base import SearchHit

# Peso de la similitud vectorial frente a la coincidencia de términos de la pregunta
_VECTOR_WEIGHT = 0.8


@dataclass(frozen=True, slots=True)
class RankedChunk:
    hit: SearchHit
    score: float


@dataclass(frozen=True, slots=True)
class RankedContext:
    chunks: list[RankedChunk]
    best_score: float

    @property
    def empty(self) -> bool:
        return not self.chunks

    def format(self) -> str:
        """Contexto para el prompt: cada fragmento numerado con su fuente."""
        return "\n\n".join(
            f"[{i}] {c.hit.title} ({c.hit.document_type})\n{c.hit.content}" for i, c in enumerate(self.chunks, 1)
        )


class ContextRanker:
    """Reordena los candidatos de la búsqueda vectorial y decide qué contexto llega al LLM.

    1. Descarta los fragmentos por debajo de la similitud mínima (no son relevantes).
    2. Combina la similitud vectorial con la cobertura de los términos de la pregunta.
    3. Elimina fragmentos repetidos y limita el total de caracteres (tokens) del contexto.
    """

    def __init__(self, min_score: float, max_context_chars: int) -> None:
        self.min_score = min_score
        self.max_context_chars = max_context_chars

    def rank(self, query: str, hits: list[SearchHit], top_k: int) -> RankedContext:
        query_stems = set(content_stems(query))
        scored: list[RankedChunk] = []
        for hit in hits:
            if hit.score < self.min_score:
                continue
            chunk_stems = set(content_stems(hit.title + " " + hit.content))
            coverage = len(query_stems & chunk_stems) / len(query_stems) if query_stems else 0.0
            scored.append(RankedChunk(hit, _VECTOR_WEIGHT * hit.score + (1 - _VECTOR_WEIGHT) * coverage))
        scored.sort(key=lambda c: c.score, reverse=True)

        selected: list[RankedChunk] = []
        seen: set[str] = set()
        total = 0
        for chunk in scored:
            fingerprint = " ".join(chunk.hit.content.split())[:200]
            if fingerprint in seen:
                continue
            if selected and total + len(chunk.hit.content) > self.max_context_chars:
                break
            selected.append(chunk)
            seen.add(fingerprint)
            total += len(chunk.hit.content)
            if len(selected) == top_k:
                break
        best = max((c.hit.score for c in selected), default=0.0)
        return RankedContext(selected, best)
