-- Enforce that activity_event is append-only.
--
-- Two mechanisms, because each covers a gap the other leaves:
--
--   1. A trigger that rejects UPDATE and DELETE. This binds every role
--      including the table owner, so it holds in local development where the
--      application and the migration run as the same user.
--
--   2. Table grants restricting the application role to INSERT and SELECT.
--      This is production defence-in-depth: even a compromised application
--      credential cannot rewrite history, and the restriction sits outside the
--      application's own code path.
--
-- Neither stops a superuser, which is why events are also hash-chained — the
-- chain makes tampering detectable when prevention is bypassed.

CREATE OR REPLACE FUNCTION activity_event_is_append_only()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'activity_event is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_activity_event_no_update
    BEFORE UPDATE ON activity_event
    FOR EACH ROW EXECUTE FUNCTION activity_event_is_append_only();

CREATE TRIGGER trg_activity_event_no_delete
    BEFORE DELETE ON activity_event
    FOR EACH ROW EXECUTE FUNCTION activity_event_is_append_only();

-- Production grants. Applied only when the application role exists, so local
-- development (single role) and CI run this migration unchanged.
DO $$
DECLARE
    -- Supplied by the Flyway placeholder `appDbRole` (see application.yml).
    -- Empty in local development and CI, where the trigger above is the whole
    -- enforcement.
    app_role TEXT := '${appDbRole}';
BEGIN
    IF app_role IS NULL OR app_role = '' THEN
        RAISE NOTICE
            'appDbRole not set; skipping activity_event grants (expected in local development)';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE NOTICE 'role % does not exist; skipping activity_event grants', app_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON activity_event FROM %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT ON activity_event TO %I', app_role);
END;
$$;
