import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app

from app.api.routes import SERVICE_VERSION, protected, router
from app.container import Services, build_services
from app.core.config import Settings, get_settings
from app.core.correlation import CORRELATION_HEADER, correlation_id_var, resolve_correlation_id
from app.core.errors import ErrorCode, ServiceError
from app.core.logging import configure_logging
from app.core.metrics import HTTP_LATENCY, HTTP_REQUESTS

logger = logging.getLogger("app")


def create_app(settings: Settings | None = None, *, services: Services | None = None) -> FastAPI:
    """Construye la aplicación. Los tests inyectan sus propios servicios (LLM, embeddings y vector
    store simulados) sin tocar el resto."""
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_format, settings.service_name)
    services = services or build_services(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        logger.info(
            "AI service started",
            extra={
                "llm_enabled": services.llm.enabled, "llm_model": services.llm.model,
                "embedding_provider": services.embeddings.provider, "embedding_model": services.embeddings.model,
                "vector_store": services.store.name, "classifier": settings.classifier_strategy,
            },
        )
        yield
        services.close()

    app = FastAPI(
        title="SupportMind AI Service",
        version=SERVICE_VERSION,
        description="Internal AI service: RAG answers, embeddings, vector search, classification, sentiment, "
                    "summarization and agent suggestions. Only the Java backend calls it (X-Internal-Api-Key).",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.services = services
    app.include_router(router)
    app.include_router(protected)
    app.mount("/metrics", make_asgi_app())
    _register_middleware(app)
    _register_error_handlers(app)
    return app


def _register_middleware(app: FastAPI) -> None:
    @app.middleware("http")
    async def correlation_and_metrics(request: Request, call_next):
        correlation_id = resolve_correlation_id(request.headers.get(CORRELATION_HEADER))
        token = correlation_id_var.set(correlation_id)
        started = time.perf_counter()
        status = 500
        try:
            try:
                response = await call_next(request)
            except Exception:
                # Se captura aquí (y no en un exception_handler global) para responder con el
                # correlation_id todavía en contexto
                logger.exception("Unexpected error while processing the request")
                response = _error(ErrorCode.INTERNAL_ERROR, "Unexpected error in the AI service")
            status = response.status_code
            response.headers[CORRELATION_HEADER] = correlation_id
            return response
        finally:
            elapsed = time.perf_counter() - started
            # Se etiqueta con la plantilla de la ruta, no con la URL, para no disparar la cardinalidad
            route = getattr(request.scope.get("route"), "path", "unmatched")
            if not request.url.path.startswith("/metrics"):
                HTTP_REQUESTS.labels(request.method, route, str(status)).inc()
                HTTP_LATENCY.labels(request.method, route).observe(elapsed)
                logger.info(
                    "HTTP request",
                    extra={"method": request.method, "path": route, "status": status,
                           "duration_ms": int(elapsed * 1000)},
                )
            correlation_id_var.reset(token)


def _error(code: ErrorCode, message: str) -> JSONResponse:
    body = {"error": {"code": code.value, "message": message, "correlation_id": correlation_id_var.get()}}
    return JSONResponse(status_code=code.status_code, content=body)


def _register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ServiceError)
    async def service_error(_: Request, ex: ServiceError) -> JSONResponse:
        log = logger.warning if ex.code.status_code >= 500 else logger.info
        log("Request failed", extra={"error_code": ex.code.value, "error_message": ex.message})
        return _error(ex.code, ex.message)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, ex: RequestValidationError) -> JSONResponse:
        # Solo los nombres de los campos: el mensaje del cliente nunca se refleja en la respuesta
        fields = ", ".join(".".join(str(p) for p in err["loc"][1:]) or "body" for err in ex.errors())
        return _error(ErrorCode.INVALID_REQUEST, f"Invalid request fields: {fields}")
