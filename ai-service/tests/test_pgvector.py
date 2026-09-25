"""Tests contra PostgreSQL + pgvector real.

Se ejecutan si existe PGVECTOR_TEST_DSN, por ejemplo con la imagen de tests en la misma red que un
contenedor pgvector/pgvector (ver README, sección Testing).
"""

import os
import uuid

import psycopg
import pytest

from app.rag.vector_store.base import ChunkRecord, VectorStoreConfigurationError, VectorStoreError
from app.rag.vector_store.pgvector import PgVectorStore
from tests.conftest import ORG_A, ORG_B

DSN = os.environ.get("PGVECTOR_TEST_DSN")

pytestmark = [
    pytest.mark.pgvector,
    pytest.mark.skipif(not DSN, reason="PGVECTOR_TEST_DSN is not set"),
]


@pytest.fixture
def schema():
    name = f"test_{uuid.uuid4().hex[:10]}"
    with psycopg.connect(DSN, autocommit=True) as conn:
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
    yield name
    with psycopg.connect(DSN, autocommit=True) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{name}" CASCADE')


def make_store(schema: str, dimensions: int = 3) -> PgVectorStore:
    store = PgVectorStore(DSN, schema, dimensions, pool_max_size=2)
    store.open()
    return store


def chunk(org, doc, index, vector, content="texto"):
    return ChunkRecord(org, doc, index, "Documento", "FAQ", content, vector)


def test_replace_search_and_delete_with_tenant_isolation(schema):
    store = make_store(schema)
    try:
        doc_a, doc_b = uuid.uuid4(), uuid.uuid4()
        chunks = [chunk(ORG_A, doc_a, 0, [1, 0, 0]), chunk(ORG_A, doc_a, 1, [0, 1, 0])]
        store.replace_document(ORG_A, doc_a, chunks, "m")
        store.replace_document(ORG_B, doc_b, [chunk(ORG_B, doc_b, 0, [1, 0, 0])], "m")

        hits = store.search(ORG_A, [1, 0.1, 0], "m", 5)
        assert [h.chunk_index for h in hits] == [0, 1]
        assert all(h.document_id == doc_a for h in hits)
        assert hits[0].score > hits[1].score
        assert store.search(ORG_A, [1, 0, 0], "other-model", 5) == []

        # Reindexar sustituye todos los fragmentos anteriores
        store.replace_document(ORG_A, doc_a, [chunk(ORG_A, doc_a, 0, [0, 0, 1], "nuevo")], "m")
        assert [h.content for h in store.search(ORG_A, [0, 0, 1], "m", 5)] == ["nuevo"]

        assert store.delete_document(ORG_B, doc_a) == 0
        assert store.delete_document(ORG_A, doc_a) == 1
        assert store.search(ORG_A, [1, 0, 0], "m", 5) == []
        assert store.is_available()
    finally:
        store.close()


def test_hnsw_search_with_many_tenants_still_returns_results_for_each(schema):
    store = make_store(schema)
    try:
        for i in range(60):
            org = ORG_A if i % 10 == 0 else uuid.uuid4()
            doc = uuid.uuid4()
            store.replace_document(org, doc, [chunk(org, doc, 0, [1, i / 60, 0])], "m")
        hits = store.search(ORG_A, [1, 0, 0], "m", 10)
        assert len(hits) == 6
    finally:
        store.close()


def test_a_table_with_other_dimensions_is_a_configuration_error(schema):
    make_store(schema, dimensions=3).close()
    store = make_store(schema, dimensions=5)
    try:
        with pytest.raises(VectorStoreConfigurationError, match="vector\\(3\\)"):
            store.search(ORG_A, [0.0] * 5, "m", 1)
        assert not store.is_available()
    finally:
        store.close()


def test_unreachable_database_is_a_transient_error():
    store = PgVectorStore("host=127.0.0.1 port=1 dbname=x user=x connect_timeout=1", "vs", 3,
                          pool_timeout_seconds=1)
    store.open()
    try:
        with pytest.raises(VectorStoreError):
            store.search(ORG_A, [1, 0, 0], "m", 1)
    finally:
        store.close()
