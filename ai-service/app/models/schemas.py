"""Contrato HTTP del servicio de IA. Los nombres de campo van en snake_case; el backend Java los
mapea con su propio ObjectMapper."""

from enum import StrEnum
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from app.services.classification.taxonomy import Category, Intent, Priority
from app.services.sentiment import Sentiment

NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ---------------------------------------------------------------- comunes


class SenderRole(StrEnum):
    CUSTOMER = "CUSTOMER"
    AGENT = "AGENT"
    AI = "AI"
    SYSTEM = "SYSTEM"


class HistoryMessage(StrictModel):
    role: SenderRole
    content: Annotated[str, StringConstraints(max_length=8000)]


class NoContextAction(StrEnum):
    ASK_MORE_INFO = "ASK_MORE_INFO"
    HANDOFF = "HANDOFF"
    INFORM = "INFORM"


class Source(BaseModel):
    document_id: UUID
    title: str
    document_type: str
    chunk_index: int
    score: float


class ErrorBody(BaseModel):
    code: str
    message: str
    correlation_id: str


class ErrorResponse(BaseModel):
    error: ErrorBody


# ---------------------------------------------------------------- clasificación y sentimiento


class ClassifyRequest(StrictModel):
    message: NonBlank = Field(max_length=8000)


class ClassificationResponse(BaseModel):
    intent: Intent
    category: Category
    priority: Priority
    confidence: float = Field(ge=0, le=1)
    strategy: str
    urgent_language: bool = False


class SentimentRequest(StrictModel):
    message: NonBlank = Field(max_length=8000)


class SentimentResponse(BaseModel):
    sentiment: Sentiment
    confidence: float = Field(ge=0, le=1)
    score: float = Field(ge=-1, le=1)
    strategy: str


# ---------------------------------------------------------------- chat (RAG)


class ChatSettings(StrictModel):
    confidence_threshold: float = Field(default=0.55, ge=0, le=1)
    no_context_action: NoContextAction = NoContextAction.ASK_MORE_INFO
    top_k: int | None = Field(default=None, ge=1, le=20)
    prompt_version: str | None = Field(default=None, pattern=r"^v\d{1,3}$")


class IntentHint(StrictModel):
    """Clasificación ya calculada por el backend al recibir el mensaje: evita repetirla."""

    intent: Intent
    category: Category
    priority: Priority
    confidence: float = Field(ge=0, le=1)
    strategy: str = "hint"


class ChatRequest(StrictModel):
    organization_id: UUID
    organization_name: NonBlank = Field(max_length=120)
    conversation_id: UUID
    message: NonBlank = Field(max_length=8000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=50)
    summary: str | None = Field(default=None, max_length=8000)
    settings: ChatSettings = Field(default_factory=ChatSettings)
    intent_hint: IntentHint | None = None
    stream: bool = False


class ChatStatus(StrEnum):
    ANSWERED = "ANSWERED"
    NO_RELEVANT_CONTEXT = "NO_RELEVANT_CONTEXT"
    AI_HANDOFF_REQUESTED = "AI_HANDOFF_REQUESTED"
    BLOCKED = "BLOCKED"


class Handoff(BaseModel):
    requested: bool
    reason: str | None = None


class Validation(BaseModel):
    valid: bool
    issues: list[str]
    grounding: float


class ChatResponse(BaseModel):
    status: ChatStatus
    answer: str
    confidence: float = Field(ge=0, le=1)
    intent: ClassificationResponse
    language: str
    sources: list[Source]
    handoff: Handoff
    model: str
    prompt_version: str | None
    validation: Validation | None
    degraded: bool
    latency_ms: int


# ---------------------------------------------------------------- resumen y sugerencias


class SummarizeRequest(StrictModel):
    organization_id: UUID
    conversation_id: UUID
    previous_summary: str | None = Field(default=None, max_length=8000)
    messages: list[HistoryMessage] = Field(min_length=1, max_length=200)


class SummarizeResponse(BaseModel):
    summary: str
    strategy: str
    message_count: int


class SuggestRequest(StrictModel):
    organization_id: UUID
    organization_name: NonBlank = Field(max_length=120)
    conversation_id: UUID
    customer_message: NonBlank = Field(max_length=8000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=50)
    summary: str | None = Field(default=None, max_length=8000)
    top_k: int | None = Field(default=None, ge=1, le=20)


class SuggestResponse(BaseModel):
    suggested_response: str
    confidence: float = Field(ge=0, le=1)
    sources: list[Source]
    has_context: bool
    model: str
    prompt_version: str | None
    language: str


# ---------------------------------------------------------------- base de conocimiento


class EmbedRequest(StrictModel):
    organization_id: UUID
    document_id: UUID
    title: NonBlank = Field(max_length=200)
    document_type: NonBlank = Field(max_length=30)
    content: NonBlank


class EmbedResponse(BaseModel):
    document_id: UUID
    chunk_count: int
    embedding_model: str
    dimensions: int
    duration_ms: int


class DeleteDocumentResponse(BaseModel):
    document_id: UUID
    deleted_chunks: int


class SearchRequest(StrictModel):
    organization_id: UUID
    query: NonBlank = Field(max_length=2000)
    top_k: int | None = Field(default=None, ge=1, le=20)


class SearchResult(BaseModel):
    document_id: UUID
    title: str
    document_type: str
    chunk_index: int
    content: str
    score: float


class SearchResponse(BaseModel):
    results: list[SearchResult]
    embedding_model: str


# ---------------------------------------------------------------- health


class ComponentHealth(BaseModel):
    provider: str
    available: bool
    model: str | None = None


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    llm: ComponentHealth
    embeddings: ComponentHealth
    vector_store: ComponentHealth
    prompts: dict[str, dict[str, object]]
