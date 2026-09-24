import logging
import time
from dataclasses import dataclass
from uuid import UUID

from app.core.errors import ErrorCode, ServiceError
from app.embeddings.base import EmbeddingError, EmbeddingService
from app.rag.chunking import TextChunker
from app.rag.vector_store.base import ChunkRecord, VectorStore, VectorStoreConfigurationError, VectorStoreError

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class IndexingResult:
    chunk_count: int
    embedding_model: str
    dimensions: int
    duration_ms: int


class DocumentIndexer:
    """Procesa un documento de la base de conocimiento: texto -> chunks -> embeddings -> vector store.

    Reindexar un documento sustituye todos sus fragmentos en una sola transacción: nunca quedan
    mezclados fragmentos de la versión anterior y de la nueva.
    """

    def __init__(self, chunker: TextChunker, embeddings: EmbeddingService, store: VectorStore) -> None:
        self.chunker = chunker
        self.embeddings = embeddings
        self.store = store

    def index(self, organization_id: UUID, document_id: UUID, title: str, document_type: str,
              content: str) -> IndexingResult:
        started = time.perf_counter()
        chunks = self.chunker.split(content)
        # El título se incluye en el texto vectorizado: "Política de reembolsos" ayuda a recuperar el
        # fragmento aunque el cuerpo no repita esas palabras
        texts = [f"{title}\n\n{chunk.content}" for chunk in chunks]
        try:
            vectors = self.embeddings.embed_documents(texts) if texts else []
            records = [
                ChunkRecord(organization_id, document_id, chunk.index, title, document_type, chunk.content, vector)
                for chunk, vector in zip(chunks, vectors, strict=True)
            ]
            self.store.replace_document(organization_id, document_id, records, self.embeddings.model)
        except EmbeddingError as ex:
            raise ServiceError(ErrorCode.EMBEDDING_FAILED, str(ex)) from ex
        except VectorStoreError as ex:
            raise ServiceError(ErrorCode.VECTOR_STORE_UNAVAILABLE, str(ex)) from ex
        except VectorStoreConfigurationError as ex:
            raise ServiceError(ErrorCode.VECTOR_STORE_MISCONFIGURED, str(ex)) from ex

        duration_ms = int((time.perf_counter() - started) * 1000)
        logger.info(
            "Knowledge document indexed",
            extra={"document_id": str(document_id), "chunks": len(records), "duration_ms": duration_ms},
        )
        return IndexingResult(len(records), self.embeddings.model, self.embeddings.dimensions, duration_ms)

    def delete(self, organization_id: UUID, document_id: UUID) -> int:
        try:
            return self.store.delete_document(organization_id, document_id)
        except VectorStoreError as ex:
            raise ServiceError(ErrorCode.VECTOR_STORE_UNAVAILABLE, str(ex)) from ex
        except VectorStoreConfigurationError as ex:
            raise ServiceError(ErrorCode.VECTOR_STORE_MISCONFIGURED, str(ex)) from ex
