import time
from uuid import UUID

from app.core.errors import ErrorCode, ServiceError
from app.core.metrics import RAG_SEARCH_LATENCY
from app.embeddings.base import EmbeddingError, EmbeddingService
from app.rag.vector_store.base import SearchHit, VectorStore, VectorStoreConfigurationError, VectorStoreError


class VectorSearchService:
    """Búsqueda semántica: vectoriza la consulta y busca en el almacén de la organización.

    Es el único punto que combina embeddings y almacén: cambiar pgvector por Qdrant, Pinecone o
    Weaviate solo requiere otra implementación de VectorStore.
    """

    def __init__(self, embeddings: EmbeddingService, store: VectorStore) -> None:
        self.embeddings = embeddings
        self.store = store

    def search(self, organization_id: UUID, query: str, limit: int) -> list[SearchHit]:
        started = time.perf_counter()
        try:
            vector = self.embeddings.embed_query(query)
            return self.store.search(organization_id, vector, self.embeddings.model, limit)
        except EmbeddingError as ex:
            raise ServiceError(ErrorCode.EMBEDDING_FAILED, str(ex)) from ex
        except VectorStoreError as ex:
            raise ServiceError(ErrorCode.VECTOR_STORE_UNAVAILABLE, str(ex)) from ex
        except VectorStoreConfigurationError as ex:
            raise ServiceError(ErrorCode.VECTOR_STORE_MISCONFIGURED, str(ex)) from ex
        finally:
            RAG_SEARCH_LATENCY.observe(time.perf_counter() - started)
