from uuid import uuid4

from app.llm.base import LlmError
from tests.conftest import HEADERS, ORG_A, ORG_B, SHIPPING_POLICY, FakeLlm


def test_every_ai_endpoint_requires_the_internal_api_key(client_factory):
    client, _ = client_factory(indexed=False)
    for path, body in [
        ("/api/v1/ai/classify", {"message": "hola"}),
        ("/api/v1/ai/sentiment", {"message": "hola"}),
        ("/api/v1/knowledge/search", {"organization_id": str(ORG_A), "query": "envío"}),
    ]:
        missing = client.post(path, json=body)
        wrong = client.post(path, json=body, headers={"X-Internal-Api-Key": "x" * 40})
        assert missing.status_code == 401, path
        assert wrong.status_code == 401, path
        assert missing.json()["error"]["code"] == "UNAUTHORIZED"


def test_health_is_public_and_reports_the_components(client_factory):
    client, _ = client_factory(indexed=False)
    body = client.get("/api/v1/health").json()
    assert body["status"] == "UP"
    assert body["llm"] == {"provider": "disabled", "available": False, "model": None}
    assert body["embeddings"]["provider"] == "hash"
    assert body["vector_store"] == {"provider": "memory", "available": True, "model": None}
    assert body["prompts"]["customer_response"]["active"] == "v2"


def test_validation_errors_do_not_echo_the_request(client_factory):
    client, _ = client_factory(indexed=False)
    response = client.post("/api/v1/ai/classify", json={"message": "", "extra": "secret"}, headers=HEADERS)
    assert response.status_code == 422
    assert "secret" not in response.text
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_classify_endpoint(client_factory):
    client, _ = client_factory(indexed=False)
    body = client.post("/api/v1/ai/classify", json={"message": "Me cobraron dos veces, es urgente"},
                       headers=HEADERS).json()
    assert body["intent"] == "PAYMENT_ISSUE"
    assert body["category"] == "BILLING"
    assert body["priority"] == "URGENT"
    assert body["urgent_language"] is True
    assert 0 < body["confidence"] <= 1


def test_sentiment_endpoint(client_factory):
    client, _ = client_factory(indexed=False)
    body = client.post("/api/v1/ai/sentiment", json={"message": "El servicio es pésimo, estoy muy molesto"},
                       headers=HEADERS).json()
    assert body["sentiment"] == "NEGATIVE"
    assert body["strategy"] == "lexicon"


def test_embed_search_and_delete_a_document(client_factory):
    client, services = client_factory(indexed=False)
    document_id = str(uuid4())
    embed = client.post("/api/v1/knowledge/embed", headers=HEADERS, json={
        "organization_id": str(ORG_A), "document_id": document_id, "title": "Política de envíos",
        "document_type": "POLICY", "content": SHIPPING_POLICY,
    })
    assert embed.status_code == 200, embed.text
    assert embed.json()["chunk_count"] >= 1
    assert embed.json()["dimensions"] == 256

    search = client.post("/api/v1/knowledge/search", headers=HEADERS,
                         json={"organization_id": str(ORG_A), "query": "¿cuánto tarda el envío?", "top_k": 2}).json()
    assert search["results"][0]["document_id"] == document_id
    assert search["embedding_model"] == "hash-256"

    other_org = client.post("/api/v1/knowledge/search", headers=HEADERS,
                            json={"organization_id": str(ORG_B), "query": "¿cuánto tarda el envío?"}).json()
    assert other_org["results"] == []

    # Otra organización no puede borrar el documento
    foreign = client.delete(f"/api/v1/knowledge/{document_id}", params={"organization_id": str(ORG_B)},
                            headers=HEADERS)
    assert foreign.json()["deleted_chunks"] == 0
    deleted = client.delete(f"/api/v1/knowledge/{document_id}", params={"organization_id": str(ORG_A)},
                            headers=HEADERS)
    assert deleted.json()["deleted_chunks"] == embed.json()["chunk_count"]
    assert services.store.chunk_count(uuid4()) == 0


def test_reindexing_replaces_all_previous_chunks(client_factory):
    client, services = client_factory(indexed=False)
    document_id = uuid4()
    long_text = " ".join(f"Frase número {i} sobre la garantía del producto." for i in range(60))
    services.indexer.index(ORG_A, document_id, "Garantía", "POLICY", long_text)
    assert services.store.chunk_count(document_id) > 1
    services.indexer.index(ORG_A, document_id, "Garantía", "POLICY", "La garantía es de un año.")
    assert services.store.chunk_count(document_id) == 1


def test_documents_larger_than_the_limit_are_rejected(client_factory):
    client, _ = client_factory(indexed=False, max_document_chars=100)
    response = client.post("/api/v1/knowledge/embed", headers=HEADERS, json={
        "organization_id": str(ORG_A), "document_id": str(uuid4()), "title": "Largo",
        "document_type": "MANUAL", "content": "x" * 101,
    })
    assert response.status_code == 422


def test_summarize_with_llm_and_extractive_fallback(client_factory):
    messages = [
        {"role": "CUSTOMER", "content": "Mi pedido 4521 llegó roto"},
        {"role": "AI", "content": "Lamento lo ocurrido, ¿puedes enviar una foto?"},
        {"role": "CUSTOMER", "content": "Ya la envié por correo"},
    ]
    payload = {"organization_id": str(ORG_A), "conversation_id": str(uuid4()), "messages": messages}

    client, _ = client_factory(FakeLlm({"summarization": "El pedido 4521 llegó roto; el cliente envió una foto."}),
                               indexed=False)
    body = client.post("/api/v1/ai/summarize", json=payload, headers=HEADERS).json()
    assert body == {"summary": "El pedido 4521 llegó roto; el cliente envió una foto.", "strategy": "llm",
                    "message_count": 3}

    client, _ = client_factory(FakeLlm({"summarization": LlmError("down")}), indexed=False)
    body = client.post("/api/v1/ai/summarize", json={**payload, "previous_summary": "Resumen previo."},
                       headers=HEADERS).json()
    assert body["strategy"] == "extractive"
    assert body["summary"].startswith("Resumen previo.")
    assert "4521" in body["summary"]
    assert "Ya la envié por correo" in body["summary"]


def test_suggest_returns_a_draft_with_sources(client_factory):
    draft = "Hola, puedes solicitar el reembolso dentro de los 30 días siguientes a la entrega."
    llm = FakeLlm({"agent_suggestion": draft})
    client, _ = client_factory(llm)
    body = client.post("/api/v1/ai/suggest", headers=HEADERS, json={
        "organization_id": str(ORG_A), "organization_name": "Acme Store", "conversation_id": str(uuid4()),
        "customer_message": "¿Hasta cuándo puedo pedir el reembolso?",
    }).json()
    assert body["has_context"] is True
    assert body["model"] == "fake-model"
    assert body["prompt_version"] == "agent_suggestion_v1"
    assert body["sources"][0]["title"] == "Política de reembolsos"
    assert "30 días" in body["suggested_response"]


def test_suggest_without_context_returns_a_template_with_low_confidence(client_factory):
    client, _ = client_factory()
    body = client.post("/api/v1/ai/suggest", headers=HEADERS, json={
        "organization_id": str(ORG_A), "organization_name": "Acme Store", "conversation_id": str(uuid4()),
        "customer_message": "¿Venden repuestos para tractores?",
    }).json()
    assert body["has_context"] is False
    assert body["sources"] == []
    assert body["confidence"] < 0.5


def test_metrics_follow_the_platform_naming(client_factory):
    client, _ = client_factory()
    client.post("/api/v1/ai/chat", headers=HEADERS, json={
        "organization_id": str(ORG_A), "organization_name": "Acme", "conversation_id": str(uuid4()),
        "message": "¿Cuánto tarda el reembolso?",
    })
    metrics = client.get("/metrics/").text
    for name in ("ai_requests_total", "ai_errors_total", "ai_latency_seconds", "rag_search_latency_seconds",
                 "llm_latency_seconds", "http_requests_total"):
        assert name in metrics, name
    assert 'ai_requests_total{operation="chat",outcome="success"}' in metrics


def test_correlation_id_is_propagated(client_factory):
    client, _ = client_factory(indexed=False)
    response = client.post("/api/v1/ai/classify", json={"message": "hola"},
                           headers={**HEADERS, "X-Correlation-Id": "abc-123"})
    assert response.headers["X-Correlation-Id"] == "abc-123"
    unsafe = client.post("/api/v1/ai/classify", json={"message": "hola"},
                         headers={**HEADERS, "X-Correlation-Id": "bad\nvalue"})
    assert unsafe.headers["X-Correlation-Id"] != "bad\nvalue"
