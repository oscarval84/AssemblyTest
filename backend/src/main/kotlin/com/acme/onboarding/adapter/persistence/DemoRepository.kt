package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class DemoRepository(private val db: JdbcClient) {

    /**
     * Whether this database has anyone who can sign in.
     *
     * The question the seeder actually needs answered, and it is deliberately
     * not `count(*) FROM app_user`. That was the original check and it silently
     * stopped working the moment `V6__vms_integration.sql` began inserting the
     * integration's own service account: every freshly migrated database then
     * had one row, the seeder decided the world was already populated, and the
     * app came up with no suppliers and no working logins. It was invisible
     * locally — those databases had been seeded before V6 existed — and it only
     * appeared on the first clean deploy, which is also what an evaluator gets
     * from `docker compose up`.
     *
     * `password_hash IS NOT NULL` is the durable form of the question. The VMS
     * account is created with no password on purpose so it can never
     * authenticate, so it is exactly the row that should not count here, and any
     * future service account created the same way is handled without touching
     * this query.
     */
    fun isEmpty(): Boolean =
        db.sql("SELECT count(*) FROM app_user WHERE password_hash IS NOT NULL")
            .query(Integer::class.java)
            .single()
            .toInt() == 0

    /**
     * Clears the operational tables, keeping the reference data that is part of
     * the product rather than part of the demo.
     *
     * `TRUNCATE` rather than `DELETE` is not an optimisation: `activity_event`
     * carries a row-level trigger rejecting deletes, and truncation is the one
     * path that bypasses it — deliberately, because it also requires table
     * ownership. In a deployed environment the application's role does not own
     * these tables, so this call fails there, which is the correct outcome for a
     * demo-only feature.
     */
    fun truncateOperationalData() {
        db.sql(
            """
            TRUNCATE TABLE
                signature_record, extraction_result, document_submission,
                program_enrollment, program_requirement, email_message,
                activity_event, user_session, invitation, password_reset_token,
                program_manager_assignment, program, supplier, app_user,
                -- vms_link deliberately holds no foreign key: it points at
                -- either a supplier or an enrollment, so CASCADE does not reach
                -- it and a reset would otherwise leave links to records that no
                -- longer exist — which the next sync reads as "already known"
                -- and quietly does nothing about.
                vms_link, integration_message, vms_field_conflict, expiry_reminder
            RESTART IDENTITY CASCADE
            """,
        ).update()

        // The integration's service account is product reference data, not part
        // of the demo world: V6 creates it, and a reset that dropped it would
        // leave the VMS sync unable to name an actor for anything it does.
        db.sql(
            """
            INSERT INTO app_user (email, full_name, role, status, password_hash)
            VALUES ('vms-sync@acme-msp.example', 'VMS integration', 'OPS', 'DEACTIVATED', NULL)
            ON CONFLICT DO NOTHING
            """,
        ).update()
    }
}
