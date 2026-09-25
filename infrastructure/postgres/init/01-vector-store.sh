#!/bin/sh
# Se ejecuta una sola vez, al inicializar el volumen de PostgreSQL.
#
# - Activa pgvector.
# - Crea el rol del servicio de IA y el esquema vector_store, del que es dueño. Ese rol no tiene
#   ningún permiso sobre las tablas de negocio (esquema public): aunque el servicio de IA quedara
#   comprometido, no podría leer usuarios, conversaciones ni tickets.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v vector_user="$VECTOR_DB_USER" -v vector_password="$VECTOR_DB_PASSWORD" <<'EOSQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE ROLE :"vector_user" LOGIN PASSWORD :'vector_password';
CREATE SCHEMA vector_store AUTHORIZATION :"vector_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;
EOSQL
