from abc import ABC, abstractmethod
from dataclasses import dataclass
from uuid import UUID


class VectorStoreError(Exception):
    """El almacén vectorial no está disponible (error transitorio)."""


class VectorStoreConfigurationError(Exception):
    """El almacén vectorial no coincide con la configuración (p. ej. otras dimensiones)."""


@dataclass(frozen=True, slots=True)
class ChunkRecord:
    """Fragmento de un documento de la base de conocimiento, listo para indexar."""

    organization_id: UUID
    document_id: UUID
    chunk_index: int
    title: str
    document_type: str
    content: str
    embedding: list[float]


@dataclass(frozen=True, slots=True)
class SearchHit:
    document_id: UUID
    title: str
    document_type: str
    chunk_index: int
    content: str
    score: float


class VectorStore(ABC):
    """Almacén de embeddings. Toda operación exige la organización: no existe forma de buscar o
    borrar fragmentos sin filtrar por tenant.

    Implementaciones: PostgreSQL + pgvector y memoria (tests). Qdrant, Pinecone o Weaviate
    serían otra implementación de esta interfaz sin cambios en el resto del servicio.
    """

    name: str

    @abstractmethod
    def replace_document(
        self, organization_id: UUID, document_id: UUID, chunks: list[ChunkRecord], embedding_model: str
    ) -> None:
        """Sustituye de forma atómica todos los fragmentos del documento."""

    @abstractmethod
    def delete_document(self, organization_id: UUID, document_id: UUID) -> int:
        """Borra los fragmentos del documento y devuelve cuántos había."""

    @abstractmethod
    def search(
        self, organization_id: UUID, embedding: list[float], embedding_model: str, limit: int
    ) -> list[SearchHit]:
        """Fragmentos de la organización más similares (coseno), ordenados de mayor a menor score."""

    @abstractmethod
    def is_available(self) -> bool: ...

    def close(self) -> None:  # pragma: no cover - por defecto no hay recursos
        return None
