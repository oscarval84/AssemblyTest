-- Store audit before/after state as `json`, not `jsonb`.
--
-- The difference is the whole reason this migration exists. `jsonb` is a parsed,
-- normalised representation: it reorders keys, drops insignificant whitespace and
-- rewrites numeric literals. It is the better type for querying, and the wrong
-- one here.
--
-- The hash chain is computed over the event's content and re-verified by reading
-- that content back out of the database. With `jsonb`, the text written and the
-- text returned differ for the same value, so every event with a state payload
-- failed its own verification — the log reported tampering that had not happened,
-- which is worse than no verification at all.
--
-- `json` keeps an exact copy of the input text, so a payload round-trips
-- byte-for-byte and the chain verifies what it claims to verify. Querying inside
-- these columns still works; only the index-backed operators are given up, and
-- the audit log is read by chain and by subject, never by a key inside the state.

ALTER TABLE activity_event
    ALTER COLUMN before_state TYPE JSON USING before_state::json,
    ALTER COLUMN after_state  TYPE JSON USING after_state::json;

-- Any event written before this migration was hashed over its pre-normalisation
-- text and will not verify. That is recorded here rather than silently repaired:
-- rewriting stored hashes to make a chain "verify" is precisely the operation the
-- chain exists to detect.
