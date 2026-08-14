-- VMS integration: the links that make sync idempotent, and the outbox that
-- makes it reliable.

-- ---------------------------------------------------------------------------
-- Links to the external system of record
-- ---------------------------------------------------------------------------
--
-- The idempotency key for both directions. A sync resolves the external id to a
-- local record through this table before doing anything, which is what makes a
-- repeated pull a no-op rather than a second supplier.
--
-- `entity_type` rather than two tables because the two link kinds are the same
-- fact — "this local thing is that remote thing" — and every query over them
-- wants both.

CREATE TABLE vms_link (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_system TEXT NOT NULL,
    entity_type     TEXT NOT NULL CHECK (entity_type IN ('SUPPLIER', 'ENROLLMENT')),
    entity_id       UUID NOT NULL,
    external_id     TEXT NOT NULL,
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One local record per external record, and one external record per local
    -- record. Both directions matter: the first stops a duplicate supplier, the
    -- second stops one supplier being claimed by two VMS records.
    UNIQUE (external_system, entity_type, external_id),
    UNIQUE (external_system, entity_type, entity_id)
);

CREATE INDEX idx_vms_link_entity ON vms_link (entity_type, entity_id);

-- ---------------------------------------------------------------------------
-- The integration outbox
-- ---------------------------------------------------------------------------
--
-- Same guarantee as the email outbox, for the same reason: the row is written in
-- the transaction that caused it, so the VMS cannot be told about an activation
-- that rolled back.
--
-- Inbound rows are recorded too, including the ones that changed nothing. "We
-- checked and there was nothing new" is what an operator needs to see when they
-- are wondering whether the integration is alive at all.

CREATE TABLE integration_message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction       TEXT NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    target_system   TEXT NOT NULL,
    message_type    TEXT NOT NULL,
    -- The external record this concerns, when there is one. Not a foreign key:
    -- it belongs to another system.
    external_ref    TEXT,
    supplier_id     UUID REFERENCES supplier (id),
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD_LETTER', 'RECEIVED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    -- Exponential backoff between attempts; a message is not eligible until now.
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_integration_pending ON integration_message (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_integration_created ON integration_message (created_at DESC);
CREATE INDEX idx_integration_supplier ON integration_message (supplier_id);

-- ---------------------------------------------------------------------------
-- Divergence between the two systems
-- ---------------------------------------------------------------------------
--
-- When an inbound value contradicts local evidence — the VMS says "Northwind
-- Staffing LLC", the approved W-9 says "Northwind Staffing Group LLC" — neither
-- side is overwritten. One of them is wrong, a human decides which, and silent
-- auto-resolution in either direction is how two systems diverge in a way nobody
-- can reconstruct afterwards.

CREATE TABLE vms_field_conflict (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id  UUID NOT NULL REFERENCES supplier (id),
    field        TEXT NOT NULL,
    local_value  TEXT,
    remote_value TEXT,
    detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ,
    resolved_by  UUID REFERENCES app_user (id),
    resolution   TEXT CHECK (resolution IN ('KEPT_LOCAL', 'ACCEPTED_REMOTE')),

    UNIQUE (supplier_id, field, detected_at)
);

CREATE INDEX idx_vms_conflict_open ON vms_field_conflict (supplier_id) WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------------------
-- The integration's own account
-- ---------------------------------------------------------------------------
--
-- The sync acts as a named principal rather than as "system", so every supplier
-- it creates and every event it writes attributes to something an access report
-- can list. It is created DEACTIVATED and with no password, which is exactly
-- right: the status gates sign-in only, so this account can never authenticate
-- and can still be the recorded actor.

INSERT INTO app_user (email, full_name, role, status, password_hash)
VALUES ('vms-sync@acme-msp.example', 'VMS integration', 'OPS', 'DEACTIVATED', NULL);
