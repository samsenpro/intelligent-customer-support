from collections.abc import Callable, Iterator
from typing import Any
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from app.container import Services, build_services
from app.core.config import Settings
from app.embeddings.hashing import HashingEmbeddingService
from app.llm.base import ChatMessage, LlmError, LlmService
from app.main import create_app
from app.rag.vector_store.memory import InMemoryVectorStore

API_KEY = "test-internal-api-key-0123456789abcdef"
HEADERS = {"X-Internal-Api-Key": API_KEY}

ORG_A = UUID("00000000-0000-0000-0000-00000000000a")
ORG_B = UUID("00000000-0000-0000-0000-00000000000b")

REFUND_POLICY = """# Política de reembolsos

Puedes solicitar el reembolso de un producto dentro de los 30 días siguientes a la entrega.
El producto debe estar sin usar y en su empaque original.

## Plazos

Una vez aprobado, el reembolso se acredita en un plazo de 5 a 10 días hábiles en el mismo medio de pago.
Los costos de envío de la devolución los asume el cliente, salvo que el producto llegue defectuoso."""

SHIPPING_POLICY = """# Política de envíos

Los pedidos se despachan en 24 horas hábiles. El envío estándar tarda de 3 a 5 días hábiles.
El envío es gratis en compras superiores a $150.000. Puedes rastrear tu pedido con el número de guía
que enviamos por correo electrónico."""


class FakeLlm(LlmService):
    """LLM simulado: responde por operación y registra las llamadas."""

    def __init__(self, responses: dict[str, str | Exception] | None = None, model: str = "fake-model") -> None:
        self.responses = responses or {}
        self._model = model
        self.calls: list[dict[str, Any]] = []

    @property
    def enabled(self) -> bool:
        return True

    @property
    def model(self) -> str | None:
        return self._model

    def complete(self, messages: list[ChatMessage], *, operation: str, json_mode: bool = False,
                 max_tokens: int | None = None) -> str:
        self.calls.append({"operation": operation, "messages": messages, "json_mode": json_mode})
        response = self.responses.get(operation, LlmError(f"no fake response for {operation}"))
        if isinstance(response, Exception):
            raise response
        return response

    def stream(self, messages: list[ChatMessage], *, operation: str,
               max_tokens: int | None = None) -> Iterator[str]:
        text = self.complete(messages, operation=operation)
        # Fragmentos de 5 caracteres, como un modelo real que genera token a token
        for i in range(0, len(text), 5):
            yield text[i : i + 5]


def make_settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "ai_service_api_key": API_KEY,
        "vector_store_provider": "memory",
        "embedding_provider": "hash",
        "embedding_dimensions": 256,
        "log_format": "text",
        "log_level": "WARNING",
    }
    values.update(overrides)
    return Settings(**values)


def make_services(llm: LlmService | None = None, **overrides: Any) -> Services:
    settings = make_settings(**overrides)
    return build_services(settings, llm=llm, embeddings=HashingEmbeddingService(settings.embedding_dimensions),
                          store=InMemoryVectorStore())


def index_knowledge(services: Services, organization_id: UUID = ORG_A) -> dict[str, UUID]:
    """Indexa la base de conocimiento de prueba y devuelve los IDs de los documentos."""
    ids = {"refund": uuid4(), "shipping": uuid4()}
    services.indexer.index(organization_id, ids["refund"], "Política de reembolsos", "POLICY", REFUND_POLICY)
    services.indexer.index(organization_id, ids["shipping"], "Política de envíos", "POLICY", SHIPPING_POLICY)
    return ids


def chat_payload(message: str, organization_id: UUID = ORG_A, **overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "organization_id": str(organization_id),
        "organization_name": "Acme Store",
        "conversation_id": str(uuid4()),
        "message": message,
    }
    payload.update(overrides)
    return payload


@pytest.fixture
def client_factory() -> Callable[..., tuple[TestClient, Services]]:
    def factory(llm: LlmService | None = None, *, indexed: bool = True, **overrides: Any):
        services = make_services(llm, **overrides)
        if indexed:
            index_knowledge(services)
        return TestClient(create_app(services.settings, services=services)), services

    return factory
