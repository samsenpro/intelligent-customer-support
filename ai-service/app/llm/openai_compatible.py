import json
import logging
import time
from collections.abc import Iterator

import httpx

from app.core.correlation import CORRELATION_HEADER, current_correlation_id
from app.core.metrics import LLM_LATENCY, LLM_REQUESTS
from app.llm.base import ChatMessage, LlmError, LlmService

logger = logging.getLogger(__name__)


class OpenAICompatibleLlmService(LlmService):
    """Cliente de cualquier API compatible con /chat/completions de OpenAI (OpenAI, Groq,
    OpenRouter, Together, Ollama, vLLM, LM Studio...), con y sin streaming."""

    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str = "",
        timeout_seconds: float = 60.0,
        temperature: float = 0.1,
        max_tokens: int = 350,
        client: httpx.Client | None = None,
    ) -> None:
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.Client(
            base_url=base_url.rstrip("/"), headers=headers, timeout=httpx.Timeout(timeout_seconds, connect=5.0)
        )

    @property
    def enabled(self) -> bool:
        return True

    @property
    def model(self) -> str | None:
        return self._model

    def complete(self, messages: list[ChatMessage], *, operation: str, json_mode: bool = False,
                 max_tokens: int | None = None) -> str:
        payload = self._payload(messages, max_tokens, stream=False)
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        started = time.perf_counter()
        try:
            response = self._client.post("/chat/completions", json=payload, headers=self._headers())
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
        except Exception as ex:
            raise self._failure(operation, started, ex) from ex
        if not isinstance(content, str) or not content.strip():
            self._record(operation, "empty", started)
            raise LlmError("LLM returned an empty response")
        self._record(operation, "success", started)
        return content

    def stream(self, messages: list[ChatMessage], *, operation: str,
               max_tokens: int | None = None) -> Iterator[str]:
        payload = self._payload(messages, max_tokens, stream=True)
        started = time.perf_counter()
        produced = False
        try:
            with self._client.stream("POST", "/chat/completions", json=payload, headers=self._headers()) as response:
                response.raise_for_status()
                for line in response.iter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    choices = json.loads(data).get("choices") or []
                    delta = (choices[0].get("delta") or {}).get("content") if choices else None
                    if delta:
                        produced = True
                        yield delta
        except LlmError:
            raise
        except GeneratorExit:
            # El consumidor dejó de leer (p. ej. el cliente cerró la conexión)
            self._record(operation, "cancelled", started)
            raise
        except Exception as ex:
            raise self._failure(operation, started, ex) from ex
        if not produced:
            self._record(operation, "empty", started)
            raise LlmError("LLM returned an empty response")
        self._record(operation, "success", started)

    def close(self) -> None:
        self._client.close()

    def _payload(self, messages: list[ChatMessage], max_tokens: int | None, *, stream: bool) -> dict:
        return {
            "model": self._model,
            "messages": messages,
            "temperature": self._temperature,
            "max_tokens": max_tokens or self._max_tokens,
            "stream": stream,
        }

    @staticmethod
    def _headers() -> dict[str, str]:
        return {CORRELATION_HEADER: current_correlation_id()}

    def _failure(self, operation: str, started: float, ex: Exception) -> LlmError:
        if isinstance(ex, httpx.TimeoutException):
            self._record(operation, "timeout", started)
            return LlmError("LLM request timed out")
        if isinstance(ex, httpx.HTTPStatusError):
            self._record(operation, "http_error", started)
            # Nunca se registra el cuerpo: puede reflejar el prompt con datos de la conversación
            return LlmError(f"LLM provider returned HTTP {ex.response.status_code}")
        self._record(operation, "error", started)
        return LlmError(f"LLM request failed: {type(ex).__name__}")

    def _record(self, operation: str, outcome: str, started: float) -> None:
        elapsed = time.perf_counter() - started
        LLM_REQUESTS.labels(operation=operation, outcome=outcome).inc()
        LLM_LATENCY.labels(operation=operation).observe(elapsed)
        logger.info(
            "LLM call finished",
            extra={"operation": operation, "outcome": outcome, "model": self._model,
                   "duration_ms": int(elapsed * 1000)},
        )
