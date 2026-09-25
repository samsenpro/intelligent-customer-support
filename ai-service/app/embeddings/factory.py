from app.core.config import Settings
from app.embeddings.base import EmbeddingService
from app.embeddings.hashing import HashingEmbeddingService
from app.embeddings.openai_compatible import OpenAICompatibleEmbeddingService


def create_embedding_service(settings: Settings) -> EmbeddingService:
    if settings.embedding_provider == "hash":
        return HashingEmbeddingService(settings.embedding_dimensions, settings.embedding_model)

    # Sin URL propia se usa la del LLM (un mismo servidor Ollama u OpenAI sirve ambas cosas)
    base_url = settings.embedding_base_url or settings.llm_base_url
    if not base_url:
        raise ValueError("EMBEDDING_PROVIDER=openai requires EMBEDDING_BASE_URL or LLM_BASE_URL")
    api_key = settings.embedding_api_key.get_secret_value() or settings.llm_api_key.get_secret_value()
    return OpenAICompatibleEmbeddingService(
        base_url=base_url,
        model=settings.embedding_model,
        dimensions=settings.embedding_dimensions,
        api_key=api_key,
        timeout_seconds=settings.embedding_timeout_seconds,
        batch_size=settings.embedding_batch_size,
        query_prefix=settings.embedding_query_prefix,
        document_prefix=settings.embedding_document_prefix,
    )
