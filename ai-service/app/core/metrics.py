from prometheus_client import Counter, Histogram

# Se definen una sola vez por proceso: el registro global de prometheus_client no admite duplicados.
# Los nombres siguen el contrato de observabilidad de la plataforma (ai_requests_total, llm_latency...).

HTTP_REQUESTS = Counter("http_requests", "HTTP requests handled", ["method", "route", "status"])
HTTP_LATENCY = Histogram("http_request_duration_seconds", "HTTP request duration", ["method", "route"])

AI_REQUESTS = Counter("ai_requests", "AI operations requested", ["operation", "outcome"])
AI_ERRORS = Counter("ai_errors", "AI operations that failed", ["operation", "error_code"])
AI_LATENCY = Histogram(
    "ai_latency_seconds",
    "End-to-end latency of each AI operation",
    ["operation"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60, 120),
)
RAG_SEARCH_LATENCY = Histogram(
    "rag_search_latency_seconds",
    "Latency of the retrieval step (query embedding + vector search)",
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
)
LLM_LATENCY = Histogram(
    "llm_latency_seconds",
    "Latency of each LLM call",
    ["operation"],
    buckets=(0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60, 120),
)
LLM_REQUESTS = Counter("llm_requests", "Requests sent to the LLM provider", ["operation", "outcome"])
EMBEDDING_LATENCY = Histogram(
    "embedding_latency_seconds",
    "Latency of each embedding batch",
    ["provider"],
    buckets=(0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
)
RAG_OUTCOMES = Counter("rag_outcomes", "Result of the RAG pipeline", ["status"])
RESPONSE_VALIDATION_FAILURES = Counter(
    "response_validation_failures", "LLM answers rejected by the response validator", ["issue"]
)
