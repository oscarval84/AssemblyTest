-- Core schema for Acme supplier onboarding.
--
-- Two conventions run through this file and are load-bearing:
--
-- 1. Document dates (issued_on, expires_on) are DATE. They are calendar facts
--    printed on a certificate, not instants. Event timestamps are TIMESTAMPTZ.
--    Conflating the two produces false expiry results, which is the one class of
--    bug this system cannot afford.
--
-- 2. Compliance status is never stored. It is computed from the current date in
--    Acme's business time zone. A stored status becomes wrong at midnight with
--    nothing having written to the row.
--
-- Status-like columns use TEXT with CHECK constraints rather than native enums,
-- so adding a value is an ordinary migration instead of a type rewrite.

-- ---------------------------------------------------------------------------
-- Programs
-- ---------------------------------------------------------------------------

CREATE TABLE program (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    description TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Suppliers
-- ---------------------------------------------------------------------------

CREATE TABLE supplier (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_name            TEXT NOT NULL,
    dba_name              TEXT,
    entity_type           TEXT,

    -- Restricted classification: encrypted at rest, only the last four are ever
    -- rendered after submission.
    tax_id_encrypted      BYTEA,
    tax_id_last4          TEXT,

    address_line1         TEXT,
    address_line2         TEXT,
    city                  TEXT,
    state                 TEXT,
    postal_code           TEXT,
    country               TEXT NOT NULL DEFAULT 'US',

    primary_contact_name  TEXT,
    primary_contact_email TEXT,
    primary_contact_phone TEXT,

    -- Onboarding state machine. Terminal good state is APPROVED; ACTIVE is
    -- expressed per enrollment, not here.
    stage                 TEXT NOT NULL DEFAULT 'INVITED'
        CHECK (stage IN ('INVITED', 'REGISTERED', 'PROFILE_SUBMITTED',
                         'DOCUMENTS_IN_PROGRESS', 'IN_REVIEW',
                         'CHANGES_REQUESTED', 'APPROVED')),

    -- Suppliers are deactivated, never hard-deleted: statutory retention
    -- outlasts the relationship.
    deactivated_at        TIMESTAMPTZ,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_supplier_stage ON supplier (stage) WHERE deactivated_at IS NULL;

-- ---------------------------------------------------------------------------
-- Users, roles and program scoping
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL,
    -- Nullable: an invited user has no password until they accept, and an
    -- SSO-federated user never will.
    password_hash TEXT,
    full_name     TEXT NOT NULL,

    role          TEXT NOT NULL
        CHECK (role IN ('ADMIN', 'OPS', 'PROGRAM_MANAGER', 'SUPPLIER_USER')),

    -- Set only for SUPPLIER_USER; enforced by the CHECK below.
    supplier_id   UUID REFERENCES supplier (id),

    status        TEXT NOT NULL DEFAULT 'INVITED'
        CHECK (status IN ('INVITED', 'ACTIVE', 'DEACTIVATED')),

    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT supplier_binding_matches_role CHECK (
        (role = 'SUPPLIER_USER' AND supplier_id IS NOT NULL)
        OR (role <> 'SUPPLIER_USER' AND supplier_id IS NULL)
    )
);

-- Email is unique case-insensitively. Login lowercases before lookup.
CREATE UNIQUE INDEX idx_app_user_email ON app_user (lower(email));
CREATE INDEX idx_app_user_supplier ON app_user (supplier_id) WHERE supplier_id IS NOT NULL;

-- A PROGRAM_MANAGER sees only the programs assigned here. This scoping is why
-- authorization cannot live in a token claim.
CREATE TABLE program_manager_assignment (
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    program_id  UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, program_id)
);

-- ---------------------------------------------------------------------------
-- Enrollments — where compliance is evaluated
-- ---------------------------------------------------------------------------

CREATE TABLE program_enrollment (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id  UUID NOT NULL REFERENCES supplier (id),
    program_id   UUID NOT NULL REFERENCES program (id),

    status       TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),

    enrolled_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,

    UNIQUE (supplier_id, program_id)
);

CREATE INDEX idx_enrollment_program ON program_enrollment (program_id);

-- ---------------------------------------------------------------------------
-- Document types and requirements
-- ---------------------------------------------------------------------------

CREATE TABLE document_type (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               TEXT NOT NULL UNIQUE,
    name               TEXT NOT NULL,
    description        TEXT,

    -- SUPPLIER-scope documents are satisfied once globally and reused across
    -- every program. PROGRAM-scope documents are satisfied per enrollment.
    scope              TEXT NOT NULL CHECK (scope IN ('SUPPLIER', 'PROGRAM')),

    -- Whether an expiry date is collected and drives the compliance engine.
    expiring           BOOLEAN NOT NULL DEFAULT FALSE,

    -- Drives handling: encryption, masking, external-processing eligibility.
    -- NOT NULL so a new document type cannot be created unclassified.
    classification     TEXT NOT NULL
        CHECK (classification IN ('RESTRICTED', 'CONFIDENTIAL', 'INTERNAL')),

    -- Deliberately nullable and unset: the correct value is a legal answer
    -- Acme's counsel owns. See architecture.md, Open questions.
    retention_months   INTEGER,

    requires_signature BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- What a program demands, and under what constraint. `constraints` is JSONB
-- because the shape differs per document type (a coverage minimum for a COI has
-- no analogue on a W-9), and v1 should not invent columns for constraints the
-- client has not confirmed yet.
CREATE TABLE program_requirement (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id       UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    document_type_id UUID NOT NULL REFERENCES document_type (id),
    constraints      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (program_id, document_type_id)
);

-- ---------------------------------------------------------------------------
-- Document submissions
-- ---------------------------------------------------------------------------

CREATE TABLE document_submission (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id         UUID NOT NULL REFERENCES supplier (id),
    document_type_id    UUID NOT NULL REFERENCES document_type (id),

    -- Set only for PROGRAM-scope documents. A SUPPLIER-scope submission is
    -- shared across every enrollment, which is the whole point.
    enrollment_id       UUID REFERENCES program_enrollment (id),

    version             INTEGER NOT NULL DEFAULT 1,
    is_current          BOOLEAN NOT NULL DEFAULT TRUE,

    storage_key         TEXT NOT NULL,
    original_filename   TEXT NOT NULL,
    content_type        TEXT NOT NULL,
    size_bytes          BIGINT NOT NULL,
    checksum_sha256     TEXT NOT NULL,

    status              TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),

    -- Calendar dates, not instants. See the header note.
    issued_on           DATE,
    expires_on          DATE,

    rejection_reason_code TEXT,
    rejection_note        TEXT,

    uploaded_by         UUID REFERENCES app_user (id),
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by         UUID REFERENCES app_user (id),
    reviewed_at         TIMESTAMPTZ,

    -- Segregation of duties: ops may upload on a supplier's behalf, but the
    -- approver must be a different person.
    CONSTRAINT reviewer_differs_from_uploader CHECK (
        reviewed_by IS NULL OR uploaded_by IS NULL OR reviewed_by <> uploaded_by
    ),
    CONSTRAINT rejection_has_reason CHECK (
        status <> 'REJECTED' OR rejection_reason_code IS NOT NULL
    )
);

-- Exactly one current submission per (supplier, type, enrollment). The COALESCE
-- lets the partial unique index treat supplier-scope rows (NULL enrollment) as a
-- single slot.
CREATE UNIQUE INDEX idx_submission_current
    ON document_submission (supplier_id, document_type_id,
                            COALESCE(enrollment_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE is_current;

CREATE INDEX idx_submission_status ON document_submission (status) WHERE is_current;
CREATE INDEX idx_submission_expiry ON document_submission (expires_on)
    WHERE is_current AND status = 'APPROVED' AND expires_on IS NOT NULL;

-- Seeded catalog so rejecting a document is one click rather than a blank box.
CREATE TABLE rejection_reason (
    code       TEXT PRIMARY KEY,
    label      TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active     BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE document_submission
    ADD CONSTRAINT fk_submission_rejection_reason
    FOREIGN KEY (rejection_reason_code) REFERENCES rejection_reason (code);

-- ---------------------------------------------------------------------------
-- Signatures
-- ---------------------------------------------------------------------------

CREATE TABLE signature_record (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_submission_id UUID NOT NULL REFERENCES document_submission (id),
    signer_user_id         UUID NOT NULL REFERENCES app_user (id),

    typed_name             TEXT NOT NULL,
    signed_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    signer_ip              TEXT,
    signer_user_agent      TEXT,

    -- Which text was actually agreed to, provable after the template changes.
    template_version       TEXT NOT NULL,
    template_sha256        TEXT NOT NULL,

    -- The executed artifact: an auditor wants the document, not an assurance
    -- that one could be reconstructed.
    executed_storage_key   TEXT NOT NULL,
    executed_sha256        TEXT NOT NULL
);

CREATE INDEX idx_signature_submission ON signature_record (document_submission_id);

-- ---------------------------------------------------------------------------
-- Sessions, invitations, password resets
-- ---------------------------------------------------------------------------

-- Server-side sessions: revocation is a DELETE, which is what "manage access
-- without developer help" actually requires. Only the hash is stored, so a
-- database leak does not yield usable session tokens.
CREATE TABLE user_session (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip           TEXT,
    user_agent   TEXT,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_session_user ON user_session (user_id);
CREATE INDEX idx_session_expiry ON user_session (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE invitation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash  TEXT NOT NULL UNIQUE,
    email       TEXT NOT NULL,
    role        TEXT NOT NULL
        CHECK (role IN ('ADMIN', 'OPS', 'PROGRAM_MANAGER', 'SUPPLIER_USER')),
    supplier_id UUID REFERENCES supplier (id),
    invited_by  UUID REFERENCES app_user (id),
    expires_at  TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invitation_email ON invitation (lower(email)) WHERE accepted_at IS NULL;

CREATE TABLE password_reset_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash  TEXT NOT NULL UNIQUE,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Set when an admin triggered the reset on the user's behalf. Ops never
    -- sees or sets the password itself.
    issued_by   UUID REFERENCES app_user (id),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reset_user ON password_reset_token (user_id) WHERE consumed_at IS NULL;

-- ---------------------------------------------------------------------------
-- Audit log — append-only, hash-chained
-- ---------------------------------------------------------------------------
--
-- Append-only is enforced two ways: the application's database role holds only
-- INSERT and SELECT here (see V2), and each event carries the hash of its
-- predecessor. Deleting or altering an event breaks the chain, which makes
-- tampering detectable rather than merely forbidden.

CREATE TABLE activity_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- One chain per supplier, plus a 'SYSTEM' chain for events with no supplier
    -- (user administration, scheduled jobs).
    chain_key      TEXT   NOT NULL,
    sequence       BIGINT NOT NULL,
    prev_hash      TEXT   NOT NULL,
    event_hash     TEXT   NOT NULL,

    actor_user_id  UUID REFERENCES app_user (id),
    -- Denormalised so the log stays readable after a user is renamed, and
    -- non-null for system actors that have no user row.
    actor_label    TEXT NOT NULL,

    action         TEXT NOT NULL,
    subject_type   TEXT NOT NULL,
    subject_id     UUID,

    before_state   JSONB,
    after_state    JSONB,

    request_origin TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (chain_key, sequence)
);

CREATE INDEX idx_activity_chain ON activity_event (chain_key, sequence DESC);
CREATE INDEX idx_activity_subject ON activity_event (subject_type, subject_id);
CREATE INDEX idx_activity_occurred ON activity_event (occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Notification outbox
-- ---------------------------------------------------------------------------
--
-- Written in the same transaction as the state change that caused it, so an
-- email about a rejection cannot exist unless the rejection was committed. A
-- queue enqueued alongside the transaction can fire on a rolled-back change.

CREATE TABLE email_message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template        TEXT NOT NULL,
    recipient_email TEXT NOT NULL,
    recipient_name  TEXT,
    subject         TEXT NOT NULL,
    body_text       TEXT NOT NULL,
    body_html       TEXT,

    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,

    supplier_id     UUID REFERENCES supplier (id)
);

CREATE INDEX idx_email_pending ON email_message (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_email_supplier ON email_message (supplier_id);

-- ---------------------------------------------------------------------------
-- AI extraction results
-- ---------------------------------------------------------------------------
--
-- Advisory only. Nothing here ever sets compliance state on its own; it
-- prefills and flags, and a human approves. `disclosed_at` records that the
-- document was transmitted to an external processor.

CREATE TABLE extraction_result (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_submission_id UUID NOT NULL REFERENCES document_submission (id),
    model                  TEXT NOT NULL,
    extracted              JSONB NOT NULL,
    confidence             NUMERIC(4, 3),
    flags                  JSONB NOT NULL DEFAULT '[]'::jsonb,
    disclosed_at           TIMESTAMPTZ NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_extraction_submission ON extraction_result (document_submission_id);
