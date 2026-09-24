import time
from collections.abc import Iterator
from contextlib import contextmanager

from app.core.errors import ErrorCode, ServiceError
from app.core.metrics import AI_ERRORS, AI_LATENCY, AI_REQUESTS


@contextmanager
def track(operation: str) -> Iterator[None]:
    """Métricas de una operación de IA: ai_requests_total, ai_errors_total y ai_latency_seconds."""
    started = time.perf_counter()
    try:
        yield
    except ServiceError as ex:
        AI_REQUESTS.labels(operation=operation, outcome="error").inc()
        AI_ERRORS.labels(operation=operation, error_code=ex.code.value).inc()
        raise
    except Exception:
        AI_REQUESTS.labels(operation=operation, outcome="error").inc()
        AI_ERRORS.labels(operation=operation, error_code=ErrorCode.INTERNAL_ERROR.value).inc()
        raise
    else:
        AI_REQUESTS.labels(operation=operation, outcome="success").inc()
    finally:
        AI_LATENCY.labels(operation=operation).observe(time.perf_counter() - started)
