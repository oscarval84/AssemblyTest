-- Records that a supplier has already been reminded about one expiring document
-- at one threshold.
--
-- Idempotency is the whole point, and it is a database guarantee here rather
-- than a convention in the sweep's code. The job runs daily and a document sits
-- inside the 30-day band for thirty of them; without this table, "we remind you
-- before it lapses" becomes thirty emails and a supplier who filters us out —
-- which costs Acme the one notification that mattered.
--
-- The threshold is part of the key because the reminders are deliberately
-- different messages: 30 days is a note, 7 days is a warning, and the day after
-- expiry is a compliance problem.

CREATE TABLE expiry_reminder (
    document_submission_id UUID        NOT NULL REFERENCES document_submission (id),
    threshold_days         INTEGER     NOT NULL,
    sent_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The expiry the reminder was about. A renewed document has a new
    -- submission row, so this is a fact about that version, not about the slot.
    expires_on             DATE        NOT NULL,

    PRIMARY KEY (document_submission_id, threshold_days)
);

CREATE INDEX idx_expiry_reminder_sent ON expiry_reminder (sent_at DESC);
