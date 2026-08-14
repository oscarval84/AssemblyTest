-- Criteria-based review.
--
-- The client was asked which three or four reasons his team rejects documents
-- for, so they could become one-click buttons, and answered a better question:
-- give Acme criteria to input, and check submissions against those. A seeded
-- catalog encodes what we guessed Acme rejects for, frozen on the day we
-- guessed it; authored criteria encode what Acme actually requires, maintained
-- by the people who own the requirement, with no deploy.

-- ---------------------------------------------------------------------------
-- Criteria, versioned as a set
-- ---------------------------------------------------------------------------
--
-- The version sits on the criterion rather than in a separate table because
-- what an auditor asks for is "the text this document was judged against", and
-- the answer has to survive every later edit. Editing therefore retires the old
-- rows and writes new ones at the next version — nothing is updated in place,
-- for the same reason the agreement template carries a version on every
-- signature.

CREATE TABLE acceptance_criterion (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_requirement_id UUID NOT NULL REFERENCES program_requirement (id) ON DELETE CASCADE,
    version                INTEGER NOT NULL,
    ordinal                INTEGER NOT NULL,
    -- Plain English, written by ops. It is read by a reviewer, by the supplier
    -- when a rejection quotes it, and by the model.
    text                   TEXT NOT NULL CHECK (length(btrim(text)) > 0),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             UUID REFERENCES app_user (id),
    -- Null for the version currently in force.
    retired_at             TIMESTAMPTZ,

    UNIQUE (program_requirement_id, version, ordinal)
);

CREATE INDEX idx_criterion_current ON acceptance_criterion (program_requirement_id)
    WHERE retired_at IS NULL;

-- ---------------------------------------------------------------------------
-- Reference documents
-- ---------------------------------------------------------------------------
--
-- A blank template the supplier fills in, or an annotated "what a good one looks
-- like" for reviewers. Showing a supplier the target before they upload is
-- cheaper than rejecting them after.

CREATE TABLE reference_document (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_requirement_id UUID NOT NULL REFERENCES program_requirement (id) ON DELETE CASCADE,
    storage_key            TEXT NOT NULL,
    original_filename      TEXT NOT NULL,
    content_type           TEXT NOT NULL,
    size_bytes             BIGINT NOT NULL,
    -- An annotated internal exemplar may quote another supplier's paperwork.
    visible_to_supplier    BOOLEAN NOT NULL DEFAULT TRUE,
    description            TEXT,
    uploaded_by            UUID REFERENCES app_user (id),
    uploaded_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Verdicts
-- ---------------------------------------------------------------------------
--
-- One row per criterion per submission. `source` records who decided: the model
-- prefills, a person confirms, and both are kept — "AI acceptance criteria"
-- describes the input to a review, not a delegation of the approval. A FAIL
-- never auto-rejects and a PASS never auto-approves.

CREATE TABLE criteria_evaluation (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_submission_id UUID NOT NULL REFERENCES document_submission (id) ON DELETE CASCADE,
    criterion_id           UUID NOT NULL REFERENCES acceptance_criterion (id),
    -- Denormalised so the verdict still reads correctly after the criteria move
    -- on: this is the text that was actually judged against.
    criterion_text         TEXT NOT NULL,
    criteria_version       INTEGER NOT NULL,
    verdict                TEXT NOT NULL CHECK (verdict IN ('PASS', 'FAIL', 'UNCLEAR')),
    -- The span the verdict relied on, so a reviewer can check it rather than
    -- trust it.
    evidence               TEXT,
    confidence             NUMERIC(3, 2),
    source                 TEXT NOT NULL CHECK (source IN ('MODEL', 'REVIEWER')),
    model                  TEXT,
    decided_by             UUID REFERENCES app_user (id),
    decided_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (document_submission_id, criterion_id)
);

CREATE INDEX idx_evaluation_submission ON criteria_evaluation (document_submission_id);

-- ---------------------------------------------------------------------------
-- Seeded criteria for the demo world's insurance requirements
-- ---------------------------------------------------------------------------
--
-- Written as Marcus would write them: specific enough to decide against, in the
-- words the supplier will read back in a rejection. The coverage minimum differs
-- per program, which is the point: the same certificate can satisfy one program
-- and fail another.

INSERT INTO acceptance_criterion (program_requirement_id, version, ordinal, text)
SELECT r.id, 1, c.ordinal, replace(c.text, '{{minimum}}',
       to_char((r.constraints ->> 'generalLiabilityMinimum')::numeric, 'FM999,999,999'))
  FROM program_requirement r
  JOIN document_type t ON t.id = r.document_type_id
 CROSS JOIN (VALUES
     (1, 'The certificate holder is Acme Inc., 400 Market Street, Boston MA.'),
     (2, 'The general liability aggregate is at least USD {{minimum}}.'),
     (3, 'Workers'' compensation coverage is present and unexpired.'),
     (4, 'The policy expiry date is at least 30 days after today.'),
     (5, 'The certificate is signed by an authorised representative of the insurer.')
 ) AS c(ordinal, text)
 WHERE t.code = 'CERTIFICATE_OF_INSURANCE'
   AND r.constraints ? 'generalLiabilityMinimum';
