package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class DemoRepository(private val db: JdbcClient) {

    fun isEmpty(): Boolean =
        db.sql("SELECT count(*) FROM app_user").query(Integer::class.java).single().toInt() == 0

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
                program_manager_assignment, program, supplier, app_user
            RESTART IDENTITY CASCADE
            """,
        ).update()
    }
}
