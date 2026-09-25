import logging
import time

import httpx

from app.core.correlation import CORRELATION_HEADER, current_correlation_id
from app.core.metrics import EMBEDDING_LATENCY
from app.embeddings.base import EmbeddingError, EmbeddingService

logger = logging.getLogger(__name__)


class OpenAICompatibleEmbeddingService(EmbeddingService):
    """Cliente de cualquier API compatible con /embeddings de OpenAI (OpenAI, Ollama, vLLM,
    LM Studio...). Comprueba que el modelo devuelva las dimensiones configuradas: un vector de otro
    tamaño no se puede comparar con los ya indexados."""

    provider = "openai"

    def __init__(
        self,
        base_url: str,
        model: str,
        dimensions: int,
        api_key: str = "",
        timeout_seconds: float = 30.0,
        batch_size: int = 16,
        query_prefix: str = "",
        document_prefix: str = "",
        client: httpx.Client | None = None,
    ) -> None:
        self._model = model
        self._dimensions = dimensions
        self._batch_size = batch_size
        self._query_prefix = query_prefix
        self._document_prefix = document_prefix
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.Client(
            base_url=base_url.rstrip("/"), headers=headers, timeout=httpx.Timeout(timeout_seconds, connect=5.0)
        )

    @property
    def model(self) -> str:
        return self._model

    @property
    def dimensions(self) -> int:
        return self._dimensions

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), self._batch_size):
            batch = [self._document_prefix + t for t in texts[start : start + self._batch_size]]
            vectors.extend(self._request(batch))
        return vectors

    def embed_query(self, text: str) -> list[float]:
        return self._request([self._query_prefix + text])[0]

    def close(self) -> None:
        self._client.close()

    def _request(self, inputs: list[str]) -> list[list[float]]:
        started = time.perf_counter()
        try:
            response = self._client.post(
                "/embeddings",
                json={"model": self._model, "input": inputs},
                headers={CORRELATION_HEADER: current_correlation_id()},
            )
            response.raise_for_status()
            data = sorted(response.json()["data"], key=lambda item: item["index"])
            vectors = [[float(x) for x in item["embedding"]] for item in data]
        except httpx.TimeoutException as ex:
            raise EmbeddingError("Embedding provider timed out") from ex
        except httpx.HTTPStatusError as ex:
            raise EmbeddingError(f"Embedding provider returned HTTP {ex.response.status_code}") from ex
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as ex:
            raise EmbeddingError(f"Embedding request failed: {type(ex).__name__}") from ex
        finally:
            EMBEDDING_LATENCY.labels(provider=self.provider).observe(time.perf_counter() - started)

        if len(vectors) != len(inputs):
            raise EmbeddingError("Embedding provider returned a different number of vectors")
        for vector in vectors:
            if len(vector) != self._dimensions:
                logger.error(
                    "Embedding dimensions mismatch: check EMBEDDING_DIMENSIONS",
                    extra={"expected": self._dimensions, "received": len(vector), "model": self._model},
                )
                raise EmbeddingError(
                    f"Model {self._model} returned {len(vector)} dimensions, expected {self._dimensions}"
                )
        return vectors
