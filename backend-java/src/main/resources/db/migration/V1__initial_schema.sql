-- SupportMind AI: esquema inicial de los datos de negocio.
-- Todas las tablas de negocio llevan organization_id: el aislamiento entre organizaciones (tenants)
-- se aplica en cada consulta. Los embeddings viven en el esquema vector_store, gestionado por el
-- servicio de IA con su propio rol (ver infrastructure/postgres).

CREATE TABLE organizations (
    id                       UUID         PRIMARY KEY,
    name                     VARCHAR(120) NOT NULL,
    slug                     VARCHAR(60)  NOT NULL,
    -- Configuración de la IA por organización
    ai_auto_reply_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    ai_confidence_threshold  NUMERIC(3,2) NOT NULL DEFAULT 0.55,
    no_context_action        VARCHAR(20)  NOT NULL DEFAULT 'ASK_MORE_INFO',
    max_failed_ai_answers    INTEGER      NOT NULL DEFAULT 2,
    sensitive_categories     JSONB        NOT NULL DEFAULT '["SECURITY", "LEGAL"]',
    handoff_on_urgent        BOOLEAN      NOT NULL DEFAULT TRUE,
    auto_ticket_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    customer_signup_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    summary_after_messages   INTEGER      NOT NULL DEFAULT 12,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_organizations_threshold CHECK (ai_confidence_threshold BETWEEN 0 AND 1),
    CONSTRAINT ck_organizations_no_context CHECK (no_context_action IN ('ASK_MORE_INFO', 'HANDOFF', 'INFORM')),
    CONSTRAINT ck_organizations_failed_answers CHECK (max_failed_ai_answers BETWEEN 1 AND 10),
    CONSTRAINT ck_organizations_summary CHECK (summary_after_messages BETWEEN 4 AND 200)
);

CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    -- El email identifica al usuario en el login: es único en toda la plataforma
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'SUPERVISOR', 'AGENT', 'CUSTOMER'))
);
CREATE INDEX ix_users_organization_id ON users (organization_id);

CREATE TABLE customers (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    user_id         UUID         REFERENCES users (id),
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(254),
    phone           VARCHAR(30),
    external_ref    VARCHAR(80),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_customers_user UNIQUE (user_id),
    CONSTRAINT uq_customers_org_email UNIQUE (organization_id, email)
);
CREATE INDEX ix_customers_organization_created ON customers (organization_id, created_at DESC);

CREATE TABLE agents (
    id                       UUID         PRIMARY KEY,
    organization_id          UUID         NOT NULL REFERENCES organizations (id),
    user_id                  UUID         NOT NULL REFERENCES users (id),
    display_name             VARCHAR(120) NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    max_active_conversations INTEGER      NOT NULL DEFAULT 10,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_agents_user UNIQUE (user_id),
    CONSTRAINT ck_agents_status CHECK (status IN ('AVAILABLE', 'BUSY', 'OFFLINE')),
    CONSTRAINT ck_agents_capacity CHECK (max_active_conversations BETWEEN 1 AND 100)
);
CREATE INDEX ix_agents_organization_id ON agents (organization_id);

CREATE TABLE conversations (
    id                        UUID         PRIMARY KEY,
    organization_id           UUID         NOT NULL REFERENCES organizations (id),
    customer_id               UUID         NOT NULL REFERENCES customers (id),
    assigned_agent_id         UUID         REFERENCES agents (id),
    status                    VARCHAR(20)  NOT NULL,
    channel                   VARCHAR(20)  NOT NULL,
    subject                   VARCHAR(200),
    -- TRUE mientras la IA atiende al cliente; pasa a FALSE al derivar a un humano
    ai_enabled                BOOLEAN      NOT NULL,
    handoff_reason            VARCHAR(40),
    handoff_at                TIMESTAMPTZ,
    failed_ai_answers         INTEGER      NOT NULL DEFAULT 0,
    last_intent               VARCHAR(30),
    last_category             VARCHAR(20),
    last_sentiment            VARCHAR(10),
    last_ai_confidence        NUMERIC(4,3),
    message_count             INTEGER      NOT NULL DEFAULT 0,
    first_customer_message_at TIMESTAMPTZ,
    first_response_at         TIMESTAMPTZ,
    last_message_at           TIMESTAMPTZ,
    resolved_at               TIMESTAMPTZ,
    version                   BIGINT       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_conversations_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_conversations_channel CHECK (channel IN ('WEB', 'WHATSAPP', 'EMAIL', 'API')),
    CONSTRAINT ck_conversations_failed_answers CHECK (failed_ai_answers >= 0)
);
CREATE INDEX ix_conversations_organization_status ON conversations (organization_id, status);
CREATE INDEX ix_conversations_organization_created ON conversations (organization_id, created_at DESC);
CREATE INDEX ix_conversations_organization_activity ON conversations (organization_id, last_message_at DESC);
CREATE INDEX ix_conversations_customer_id ON conversations (customer_id);
CREATE INDEX ix_conversations_assigned_agent_id ON conversations (assigned_agent_id);

CREATE TABLE messages (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_type     VARCHAR(10) NOT NULL,
    sender_id       UUID,
    content         TEXT        NOT NULL,
    metadata        JSONB       NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_messages_sender_type CHECK (sender_type IN ('CUSTOMER', 'AGENT', 'AI', 'SYSTEM')),
    CONSTRAINT ck_messages_content CHECK (char_length(content) BETWEEN 1 AND 8000)
);
CREATE INDEX ix_messages_conversation_created ON messages (conversation_id, created_at);
CREATE INDEX ix_messages_organization_created ON messages (organization_id, created_at);

CREATE TABLE conversation_summaries (
    id                       UUID        PRIMARY KEY,
    organization_id          UUID        NOT NULL REFERENCES organizations (id),
    conversation_id          UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    summary                  TEXT        NOT NULL,
    summarized_message_count INTEGER     NOT NULL,
    strategy                 VARCHAR(20) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_conversation_summaries_conversation UNIQUE (conversation_id)
);

CREATE TABLE tickets (
    id                UUID         PRIMARY KEY,
    organization_id   UUID         NOT NULL REFERENCES organizations (id),
    conversation_id   UUID         REFERENCES conversations (id),
    customer_id       UUID         NOT NULL REFERENCES customers (id),
    assigned_agent_id UUID         REFERENCES agents (id),
    subject           VARCHAR(200) NOT NULL,
    description       TEXT         NOT NULL,
    priority          VARCHAR(10)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    category          VARCHAR(20)  NOT NULL,
    source            VARCHAR(30)  NOT NULL,
    resolved_at       TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_tickets_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_tickets_category
        CHECK (category IN ('GENERAL', 'BILLING', 'SHIPPING', 'PRODUCT', 'TECHNICAL', 'ACCOUNT', 'SECURITY', 'LEGAL')),
    CONSTRAINT ck_tickets_source CHECK (source IN ('MANUAL', 'AI_HANDOFF', 'AUTO_CLASSIFICATION')),
    CONSTRAINT ck_tickets_description CHECK (char_length(description) BETWEEN 1 AND 8000)
);
CREATE INDEX ix_tickets_organization_status ON tickets (organization_id, status);
CREATE INDEX ix_tickets_organization_created ON tickets (organization_id, created_at DESC);
CREATE INDEX ix_tickets_customer_id ON tickets (customer_id);
CREATE INDEX ix_tickets_conversation_id ON tickets (conversation_id);
CREATE INDEX ix_tickets_assigned_agent_id ON tickets (assigned_agent_id);
-- Como máximo un ticket abierto por conversación: la creación automática no puede duplicarlo
CREATE UNIQUE INDEX uq_tickets_open_per_conversation ON tickets (conversation_id)
    WHERE conversation_id IS NOT NULL AND status NOT IN ('RESOLVED', 'CLOSED');

-- Historial de cambios de cada ticket
CREATE TABLE ticket_events (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    ticket_id       UUID        NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    actor_user_id   UUID        REFERENCES users (id),
    field           VARCHAR(30) NOT NULL,
    old_value       VARCHAR(200),
    new_value       VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_ticket_events_ticket_created ON ticket_events (ticket_id, created_at);

CREATE TABLE knowledge_documents (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    -- Estado de la indexación en el vector store (chunks + embeddings en el servicio de IA)
    index_status    VARCHAR(20)  NOT NULL DEFAULT 'NOT_INDEXED',
    chunk_count     INTEGER      NOT NULL DEFAULT 0,
    embedding_model VARCHAR(100),
    indexed_at      TIMESTAMPTZ,
    index_error     VARCHAR(300),
    created_by      UUID         REFERENCES users (id),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_knowledge_type CHECK (type IN ('FAQ', 'ARTICLE', 'POLICY', 'MANUAL', 'PRODUCT', 'PROCEDURE')),
    CONSTRAINT ck_knowledge_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_index_status CHECK (index_status IN ('NOT_INDEXED', 'PENDING', 'INDEXED', 'FAILED')),
    CONSTRAINT ck_knowledge_content CHECK (char_length(content) BETWEEN 1 AND 200000)
);
CREATE INDEX ix_knowledge_organization_status ON knowledge_documents (organization_id, status);
CREATE INDEX ix_knowledge_organization_updated ON knowledge_documents (organization_id, updated_at DESC);

-- Cada respuesta generada por la IA (base de las métricas de resolución, confianza y escalamiento)
CREATE TABLE ai_responses (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    conversation_id UUID         NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    message_id      UUID         REFERENCES messages (id) ON DELETE SET NULL,
    status          VARCHAR(30)  NOT NULL,
    confidence      NUMERIC(4,3) NOT NULL,
    intent          VARCHAR(30),
    model           VARCHAR(100),
    prompt_version  VARCHAR(60),
    handoff_reason  VARCHAR(40),
    latency_ms      INTEGER      NOT NULL,
    degraded        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_ai_responses_status
        CHECK (status IN ('ANSWERED', 'NO_RELEVANT_CONTEXT', 'AI_HANDOFF_REQUESTED', 'BLOCKED', 'UNAVAILABLE'))
);
CREATE INDEX ix_ai_responses_organization_created ON ai_responses (organization_id, created_at);
CREATE INDEX ix_ai_responses_conversation_id ON ai_responses (conversation_id);

-- Sugerencias de respuesta para agentes: el agente las acepta, edita o rechaza
CREATE TABLE ai_suggestions (
    id               UUID         PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations (id),
    conversation_id  UUID         NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    requested_by     UUID         NOT NULL REFERENCES users (id),
    content          TEXT         NOT NULL,
    confidence       NUMERIC(4,3) NOT NULL,
    sources          JSONB        NOT NULL DEFAULT '[]',
    status           VARCHAR(20)  NOT NULL,
    model            VARCHAR(100),
    final_message_id UUID         REFERENCES messages (id) ON DELETE SET NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_ai_suggestions_status CHECK (status IN ('PENDING', 'ACCEPTED', 'EDITED', 'REJECTED'))
);
CREATE INDEX ix_ai_suggestions_conversation_id ON ai_suggestions (conversation_id, created_at DESC);

CREATE TABLE audit_logs (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    user_id         UUID,
    event           VARCHAR(40) NOT NULL,
    entity_type     VARCHAR(40),
    entity_id       UUID,
    ip_address      VARCHAR(45),
    metadata        JSONB       NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_logs_organization_created ON audit_logs (organization_id, created_at DESC);
