import hashlib
import math
import time
from collections import Counter

from app.core.metrics import EMBEDDING_LATENCY
from app.core.text import content_stems
from app.embeddings.base import EmbeddingService


class HashingEmbeddingService(EmbeddingService):
    """Embeddings locales por "feature hashing" de stems y bigramas de stems.

    No necesitan modelo, GPU ni red, y son deterministas: sirven para desarrollo, tests y entornos
    sin proveedor. Capturan coincidencias léxicas (con tolerancia a plurales y tildes), no sinónimos:
    para búsqueda semántica real se usa un modelo de embeddings (EMBEDDING_PROVIDER=openai).
    """

    provider = "hash"

    def __init__(self, dimensions: int = 768, model: str | None = None) -> None:
        self._dimensions = dimensions
        self._model = model or f"hash-{dimensions}"

    @property
    def model(self) -> str:
        return self._model

    @property
    def dimensions(self) -> int:
        return self._dimensions

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        started = time.perf_counter()
        vectors = [self._embed(text) for text in texts]
        EMBEDDING_LATENCY.labels(provider=self.provider).observe(time.perf_counter() - started)
        return vectors

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]

    def _embed(self, text: str) -> list[float]:
        stems = content_stems(text)
        features: Counter[str] = Counter(stems)
        # Los bigramas aportan algo de orden ("tarjeta credito" frente a "credito" suelto)
        features.update({f"{a} {b}": 0.5 for a, b in zip(stems, stems[1:], strict=False)})

        vector = [0.0] * self._dimensions
        for feature, weight in features.items():
            digest = hashlib.blake2b(feature.encode(), digest_size=8).digest()
            index = int.from_bytes(digest[:4], "big") % self._dimensions
            sign = 1.0 if digest[4] & 1 else -1.0
            # Frecuencia amortiguada: repetir una palabra no debe dominar el vector
            vector[index] += sign * (1.0 + math.log(weight)) if weight >= 1 else sign * weight
        norm = math.sqrt(sum(v * v for v in vector))
        return [v / norm for v in vector] if norm else vector
