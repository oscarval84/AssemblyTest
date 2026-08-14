-- What a rejection is grounded in.
--
-- The seeded `rejection_reason` catalog was built before the client answered the
-- question it was guessing at. Asked which three or four reasons his team rejects
-- documents for, he described something better: let Acme author acceptance
-- criteria per program, and check submissions against those. Since then the
-- product has had two vocabularies for the same act — a criterion the reviewer
-- actually failed the document on, and a catalog code the schema forced them to
-- pick anyway. The UI papered over it by sending a fixed code with every
-- criterion-based rejection, so a supplier told their signature was missing read
-- "coverage limits below the program minimum".
--
-- A rejection is now grounded in exactly one of the two:
--
--   * an authored criterion — the primary path, in Acme's own words, versioned,
--     and quotable back to the supplier verbatim; or
--   * a catalog reason — for what criteria cannot express, which is real and
--     narrow: an illegible scan, or the wrong document entirely.
--
-- Both remain first-class. The catalog is not deprecated; it is scoped.

ALTER TABLE document_submission
    ADD COLUMN rejection_criterion_id UUID REFERENCES acceptance_criterion (id);

-- The old constraint demanded a catalog code on every rejection, which is what
-- forced the fixed-code workaround. The new one demands *grounds* — either kind
-- — and still refuses a rejection with neither, because "rejected, no reason
-- given" is the experience this product exists to replace.
ALTER TABLE document_submission DROP CONSTRAINT rejection_has_reason;

ALTER TABLE document_submission
    ADD CONSTRAINT rejection_has_grounds CHECK (
        status <> 'REJECTED'
        OR rejection_reason_code IS NOT NULL
        OR rejection_criterion_id IS NOT NULL
    );

-- Grounding one rejection in two different things is not a state the product
-- has a meaning for, and the reviewer's own words would be ambiguous to the
-- supplier reading them.
ALTER TABLE document_submission
    ADD CONSTRAINT rejection_grounds_are_exclusive CHECK (
        rejection_reason_code IS NULL OR rejection_criterion_id IS NULL
    );

CREATE INDEX idx_submission_rejection_criterion
    ON document_submission (rejection_criterion_id)
    WHERE rejection_criterion_id IS NOT NULL;
