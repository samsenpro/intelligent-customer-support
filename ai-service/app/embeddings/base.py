from abc import ABC, abstractmethod


class EmbeddingError(Exception):
    """No se pudieron generar los embeddings (proveedor caído, respuesta inválida...)."""


class EmbeddingService(ABC):
    """Genera los vectores de consultas y documentos. El resto del servicio solo depende de esta
    interfaz; el proveedor se elige con EMBEDDING_PROVIDER y EMBEDDING_MODEL."""

    provider: str

    @property
    @abstractmethod
    def model(self) -> str: ...

    @property
    @abstractmethod
    def dimensions(self) -> int: ...

    @abstractmethod
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        """Vectores de los fragmentos a indexar, en el mismo orden."""

    @abstractmethod
    def embed_query(self, text: str) -> list[float]:
        """Vector de una consulta de búsqueda."""

    def close(self) -> None:  # pragma: no cover - por defecto no hay recursos
        return None
