import json
import re
from abc import ABC, abstractmethod
from collections.abc import Iterator
from typing import Any, TypedDict


class LlmError(Exception):
    """El LLM no respondió o su respuesta no es utilizable."""


class ChatMessage(TypedDict):
    role: str
    content: str


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)


class LlmService(ABC):
    """Abstracción del proveedor de LLM. Los servicios solo dependen de esta interfaz; el proveedor
    se elige por configuración (LLM_BASE_URL, LLM_MODEL, LLM_API_KEY)."""

    @property
    @abstractmethod
    def enabled(self) -> bool: ...

    @property
    @abstractmethod
    def model(self) -> str | None: ...

    @abstractmethod
    def complete(self, messages: list[ChatMessage], *, operation: str, json_mode: bool = False,
                 max_tokens: int | None = None) -> str:
        """Texto completo generado por el modelo."""

    @abstractmethod
    def stream(self, messages: list[ChatMessage], *, operation: str,
               max_tokens: int | None = None) -> Iterator[str]:
        """Fragmentos del texto a medida que el modelo los genera."""

    def complete_json(self, messages: list[ChatMessage], *, operation: str,
                      max_tokens: int | None = None) -> dict[str, Any]:
        return parse_json_object(self.complete(messages, operation=operation, json_mode=True, max_tokens=max_tokens))

    def close(self) -> None:  # pragma: no cover - por defecto no hay recursos
        return None


def parse_json_object(raw: str) -> dict[str, Any]:
    """Extrae el objeto JSON de la respuesta, tolerando bloques de Markdown o texto alrededor."""
    text = _FENCE.sub("", raw.strip())
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise LlmError("LLM response does not contain a JSON object")
    try:
        value = json.loads(text[start : end + 1])
    except json.JSONDecodeError as ex:
        raise LlmError("LLM response is not valid JSON") from ex
    if not isinstance(value, dict):
        raise LlmError("LLM response is not a JSON object")
    return value


class DisabledLlmService(LlmService):
    """Sin proveedor configurado: los servicios recurren a sus algoritmos locales (respuestas
    extractivas, clasificación por reglas y modelo, resumen extractivo)."""

    @property
    def enabled(self) -> bool:
        return False

    @property
    def model(self) -> str | None:
        return None

    def complete(self, messages: list[ChatMessage], *, operation: str, json_mode: bool = False,
                 max_tokens: int | None = None) -> str:
        raise LlmError("LLM is not configured")

    def stream(self, messages: list[ChatMessage], *, operation: str,
               max_tokens: int | None = None) -> Iterator[str]:
        raise LlmError("LLM is not configured")
