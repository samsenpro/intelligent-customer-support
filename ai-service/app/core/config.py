from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# Similitud mínima por defecto según el proveedor de embeddings: los vectores por hashing de términos
# dan similitudes mucho más bajas que un modelo neuronal para textos igual de relacionados. El valor de
# "openai" está medido con paraphrase-multilingual (Ollama) sobre la base de conocimiento de demo:
# preguntas ajenas <= 0.28, relevantes >= 0.39. Otro modelo puede necesitar otro valor (RAG_MIN_SCORE)
_DEFAULT_MIN_SCORE = {"hash": 0.10, "openai": 0.35}


class Settings(BaseSettings):
    """Configuración del servicio. Todo valor sensible llega por variables de entorno."""

    # Una variable vacía (p. ej. RAG_MIN_SCORE= en el .env) equivale a no definirla
    model_config = SettingsConfigDict(extra="ignore", case_sensitive=False, env_ignore_empty=True)

    service_name: str = "supportmind-ai-service"
    log_level: str = "INFO"
    log_format: str = Field(default="json", pattern="^(json|text)$")

    # Clave interna compartida con el backend Java (cabecera X-Internal-Api-Key)
    ai_service_api_key: SecretStr = Field(min_length=32)

    # ---- LLM (opcional). Sin LLM_BASE_URL o LLM_MODEL las respuestas son extractivas ----
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = ""
    llm_base_url: str = ""
    llm_timeout_seconds: float = Field(default=60.0, gt=0)
    llm_temperature: float = Field(default=0.1, ge=0, le=2)
    llm_max_tokens: int = Field(default=350, gt=0, le=4096)

    # ---- Embeddings ----
    embedding_provider: str = Field(default="hash", pattern="^(hash|openai)$")
    embedding_model: str = "hash-768"
    embedding_base_url: str = ""
    embedding_api_key: SecretStr = SecretStr("")
    embedding_dimensions: int = Field(default=768, ge=8, le=4096)
    embedding_timeout_seconds: float = Field(default=30.0, gt=0)
    embedding_batch_size: int = Field(default=16, gt=0, le=256)
    # Algunos modelos (nomic-embed-text, e5...) rinden mejor con un prefijo distinto para consultas y documentos
    embedding_query_prefix: str = ""
    embedding_document_prefix: str = ""

    # ---- Vector store ----
    vector_store_provider: str = Field(default="pgvector", pattern="^(pgvector|memory)$")
    vector_db_host: str = "localhost"
    vector_db_port: int = 5432
    vector_db_name: str = "supportmind"
    vector_db_user: str = "supportmind_ai"
    vector_db_password: SecretStr = SecretStr("")
    vector_db_schema: str = Field(default="vector_store", pattern="^[a-z_][a-z0-9_]{0,62}$")
    vector_db_pool_max_size: int = Field(default=5, gt=0, le=50)

    # ---- RAG ----
    vector_search_top_k: int = Field(default=4, ge=1, le=20)
    # Se recuperan top_k x multiplicador candidatos y el ranking se queda con los mejores top_k
    rag_candidate_multiplier: int = Field(default=3, ge=1, le=10)
    rag_min_score: float | None = Field(default=None, ge=0, le=1)
    rag_max_context_chars: int = Field(default=6000, gt=0)
    chunk_size_chars: int = Field(default=800, ge=200, le=4000)
    chunk_overlap_chars: int = Field(default=120, ge=0, le=1000)

    # ---- Clasificación y sentimiento ----
    classifier_strategy: str = Field(default="hybrid", pattern="^(hybrid|rules|model|llm)$")
    # En modo hybrid, si reglas y modelo no alcanzan esta confianza se consulta al LLM (si hay)
    classifier_llm_threshold: float = Field(default=0.6, ge=0, le=1)
    sentiment_strategy: str = Field(default="lexicon", pattern="^(lexicon|llm)$")

    # ---- Prompts: versión por plantilla, p. ej. "customer_response=v1,agent_suggestion=v1" ----
    prompt_versions: str = ""

    # ---- Límites de entrada ----
    max_message_chars: int = Field(default=4000, gt=0)
    max_document_chars: int = Field(default=200_000, gt=0)
    max_history_messages: int = Field(default=10, ge=0, le=50)

    @property
    def llm_enabled(self) -> bool:
        return bool(self.llm_base_url.strip() and self.llm_model.strip())

    @property
    def effective_min_score(self) -> float:
        if self.rag_min_score is not None:
            return self.rag_min_score
        return _DEFAULT_MIN_SCORE[self.embedding_provider]

    @property
    def prompt_version_overrides(self) -> dict[str, str]:
        overrides: dict[str, str] = {}
        for item in self.prompt_versions.split(","):
            name, _, version = item.partition("=")
            if name.strip() and version.strip():
                overrides[name.strip()] = version.strip()
        return overrides


@lru_cache
def get_settings() -> Settings:
    return Settings()
