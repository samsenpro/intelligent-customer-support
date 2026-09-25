import logging
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from uuid import UUID

import psycopg
from psycopg import sql
from psycopg_pool import ConnectionPool, PoolTimeout

from app.rag.vector_store.base import (
    ChunkRecord,
    SearchHit,
    VectorStore,
    VectorStoreConfigurationError,
    VectorStoreError,
)

logger = logging.getLogger(__name__)


class PgVectorStore(VectorStore):
    """Almacén vectorial sobre PostgreSQL + pgvector, en la misma base de datos que los datos de
    negocio pero en su propio esquema y con un rol que solo tiene acceso a ese esquema.

    La tabla se crea al primer uso con las dimensiones configuradas. Si ya existe con otras
    dimensiones el servicio no mezcla vectores incompatibles: responde con un error de
    configuración hasta que se reindexe o se corrija EMBEDDING_DIMENSIONS.
    """

    name = "pgvector"

    def __init__(self, conninfo: str, schema: str, dimensions: int, pool_max_size: int = 5,
                 pool_timeout_seconds: float = 5.0) -> None:
        self._schema = schema
        self._dimensions = dimensions
        self._table = sql.Identifier(schema, "knowledge_chunks")
        self._pool = ConnectionPool(
            conninfo, min_size=1, max_size=pool_max_size, timeout=pool_timeout_seconds, open=False,
            kwargs={"autocommit": False, "connect_timeout": 5},
        )
        self._ready = False
        self._ready_lock = threading.Lock()

    def open(self) -> None:
        # Sin esperar: si PostgreSQL aún no está listo el servicio arranca igual y el esquema se
        # prepara en el primer uso
        self._pool.open(wait=False)
        try:
            self._ensure_ready()
        except (VectorStoreError, VectorStoreConfigurationError) as ex:
            logger.warning("Vector store not ready at startup: %s", ex)

    def replace_document(
        self, organization_id: UUID, document_id: UUID, chunks: list[ChunkRecord], embedding_model: str
    ) -> None:
        self._ensure_ready()
        rows = [
            (c.organization_id, c.document_id, c.chunk_index, c.title, c.document_type, c.content,
             _vector_literal(c.embedding), embedding_model)
            for c in chunks
        ]
        insert = sql.SQL(
            "INSERT INTO {} (organization_id, document_id, chunk_index, title, document_type, content, "
            "embedding, embedding_model) VALUES (%s, %s, %s, %s, %s, %s, %s::vector, %s)"
        ).format(self._table)
        with self._connection() as conn, conn.transaction(), conn.cursor() as cur:
            cur.execute(
                sql.SQL("DELETE FROM {} WHERE organization_id = %s AND document_id = %s").format(self._table),
                (organization_id, document_id),
            )
            if rows:
                cur.executemany(insert, rows)

    def delete_document(self, organization_id: UUID, document_id: UUID) -> int:
        self._ensure_ready()
        with self._connection() as conn, conn.transaction(), conn.cursor() as cur:
            cur.execute(
                sql.SQL("DELETE FROM {} WHERE organization_id = %s AND document_id = %s").format(self._table),
                (organization_id, document_id),
            )
            return cur.rowcount

    def search(
        self, organization_id: UUID, embedding: list[float], embedding_model: str, limit: int
    ) -> list[SearchHit]:
        self._ensure_ready()
        query = sql.SQL(
            "SELECT document_id, title, document_type, chunk_index, content, 1 - (embedding <=> %(q)s::vector) "
            "FROM {} WHERE organization_id = %(org)s AND embedding_model = %(model)s "
            "ORDER BY embedding <=> %(q)s::vector LIMIT %(limit)s"
        ).format(self._table)
        params = {"q": _vector_literal(embedding), "org": organization_id, "model": embedding_model, "limit": limit}
        with self._connection() as conn, conn.transaction(), conn.cursor() as cur:
            # Con el índice HNSW el filtro por organización se aplica después del recorrido del grafo:
            # el escaneo iterativo (pgvector >= 0.8) sigue buscando hasta reunir `limit` filas del tenant
            cur.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
            cur.execute(query, params)
            rows = cur.fetchall()
        hits = [SearchHit(r[0], r[1], r[2], r[3], r[4], float(r[5])) for r in rows]
        # relaxed_order puede devolver el orden ligeramente alterado
        hits.sort(key=lambda hit: hit.score, reverse=True)
        return hits

    def is_available(self) -> bool:
        try:
            self._ensure_ready()
            with self._connection() as conn:
                conn.execute("SELECT 1")
            return True
        except (VectorStoreError, VectorStoreConfigurationError):
            return False

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def _connection(self) -> Iterator[psycopg.Connection]:
        """Conexión del pool; los fallos de red o de PostgreSQL se traducen a VectorStoreError
        (transitorio: el backend Java reintenta)."""
        try:
            with self._pool.connection() as conn:
                yield conn
        except PoolTimeout as ex:
            raise VectorStoreError("Vector database is unreachable") from ex
        except psycopg.OperationalError as ex:
            raise VectorStoreError(f"Vector database error: {type(ex).__name__}") from ex

    def _ensure_ready(self) -> None:
        if self._ready:
            return
        with self._ready_lock:
            if self._ready:
                return
            try:
                with self._connection() as conn, conn.transaction():
                    self._create_schema(conn)
                    self._check_dimensions(conn)
            except psycopg.errors.InsufficientPrivilege as ex:
                raise VectorStoreConfigurationError(
                    f"The vector database role cannot use schema '{self._schema}'"
                ) from ex
            self._ready = True
            logger.info("Vector store ready", extra={"schema": self._schema, "dimensions": self._dimensions})

    def _create_schema(self, conn: psycopg.Connection) -> None:
        # En Docker el esquema lo crea el script de inicialización de PostgreSQL y el rol del servicio no
        # puede crear esquemas: solo se intenta si no existe (desarrollo local con un superusuario)
        exists = conn.execute("SELECT 1 FROM pg_namespace WHERE nspname = %s", (self._schema,)).fetchone()
        if exists is None:
            conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(self._schema)))
        conn.execute(
            sql.SQL(
                """
                CREATE TABLE IF NOT EXISTS {table} (
                    id              BIGSERIAL PRIMARY KEY,
                    organization_id UUID        NOT NULL,
                    document_id     UUID        NOT NULL,
                    chunk_index     INTEGER     NOT NULL CHECK (chunk_index >= 0),
                    title           TEXT        NOT NULL,
                    document_type   TEXT        NOT NULL,
                    content         TEXT        NOT NULL,
                    embedding       vector({dims}) NOT NULL,
                    embedding_model TEXT        NOT NULL,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_knowledge_chunks_document_chunk UNIQUE (document_id, chunk_index)
                )
                """
            ).format(table=self._table, dims=sql.Literal(self._dimensions))
        )
        conn.execute(
            sql.SQL("CREATE INDEX IF NOT EXISTS ix_knowledge_chunks_org_model ON {} (organization_id, embedding_model)")
            .format(self._table)
        )
        conn.execute(
            sql.SQL("CREATE INDEX IF NOT EXISTS ix_knowledge_chunks_embedding ON {} "
                    "USING hnsw (embedding vector_cosine_ops)").format(self._table)
        )

    def _check_dimensions(self, conn: psycopg.Connection) -> None:
        row = conn.execute(
            "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
            "WHERE a.attrelid = %s::regclass AND a.attname = 'embedding'",
            (f"{self._schema}.knowledge_chunks",),
        ).fetchone()
        expected = f"vector({self._dimensions})"
        if row is None or row[0] != expected:
            raise VectorStoreConfigurationError(
                f"knowledge_chunks.embedding is {row[0] if row else 'missing'}, expected {expected}: "
                "reindex the knowledge base or fix EMBEDDING_DIMENSIONS"
            )


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{v:.7g}" for v in vector) + "]"
