package com.acme.onboarding.flow

import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.criteria.CriteriaReviewService
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.demo.DemoDataSeeder
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.domain.user.Role
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The demo world seeds itself on a freshly migrated database.
 *
 * Every other test in this suite runs with seeding **off**, which is what let a
 * real defect ship: `DemoRepository.isEmpty()` asked `count(*) FROM app_user`,
 * and `V6__vms_integration.sql` started inserting the integration's service
 * account into that table. From then on a clean database was never empty by that
 * measure, the seeder returned in silence, and the application came up with no
 * suppliers and no account anyone could sign in with.
 *
 * It was invisible in development, because those databases had been seeded
 * before V6 existed and simply gained a row afterwards. It appeared on the first
 * deploy to a new database — which is also what a reviewer gets from
 * `docker compose up`, and the brief says plainly that they will drive this
 * themselves.
 *
 * So this test exists to make the clean-database path one that CI walks. It is
 * the only test that runs with seeding on, and that is the whole point of it.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=true"])
class DemoSeedTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var authentication: AuthenticationService
    @Autowired private lateinit var criteria: CriteriaReviewService
    @Autowired private lateinit var review: DocumentReviewService

    @Test
    fun `a freshly migrated database seeds a world an evaluator can sign into`() {
        // The check that regressed. A migration adding any row to app_user must
        // not be able to convince the seeder that this database is in use.
        val demoLogins = DemoDataSeeder.DEMO_ACCOUNTS

        assertTrue(demoLogins.isNotEmpty(), "the sign-in screen offers accounts, so they must exist")

        demoLogins.forEach { account ->
            val session = authentication.login(account.email, DemoDataSeeder.DEMO_PASSWORD)
            assertEquals(
                account.email.lowercase(),
                session.actor.email.lowercase(),
                "every account listed on the sign-in screen has to actually sign in",
            )
        }
    }

    /**
     * The client's answer 2 asked for criteria Acme inputs, and this is the test
     * that the demo world actually contains any.
     *
     * `V7__acceptance_criteria.sql` seeded them by joining `program_requirement`,
     * which Flyway runs before the programs exist — so every freshly migrated
     * database had none, every review dialog said "no acceptance criteria are
     * set", and the feature the whole cycle-time argument rests on was invisible.
     * It survived because the original development database happened to have
     * programs before that migration ran.
     */
    @Test
    fun `the seeded world has the criteria a review is checked against`() {
        val ops = authentication
            .login("marcus.lee@acme-msp.example", DemoDataSeeder.DEMO_PASSWORD)
            .actor

        val certificate = review.queue(ops).firstOrNull { it.documentTypeCode == "CERTIFICATE_OF_INSURANCE" }

        assertTrue(certificate != null, "the demo world must contain a certificate waiting to be reviewed")

        val checklist = criteria.checklist(ops, certificate.submissionId)
        assertTrue(
            checklist.criteria.isNotEmpty(),
            "a certificate requirement with a coverage minimum must carry the criteria to check it against",
        )
        assertTrue(
            checklist.criteria.any { it.text.contains("aggregate") },
            "the coverage criterion is the one the rejection quotes: " + checklist.criteria.map { it.text },
        )
    }

    @Test
    fun `the seeded world has suppliers to look at, not just accounts`() {
        val ops = authentication
            .login("marcus.lee@acme-msp.example", DemoDataSeeder.DEMO_PASSWORD)
            .actor

        assertEquals(Role.OPS, ops.role)

        // An empty pipeline is a working app with nothing in it, which is the
        // failure this test was written for: the accounts and the world seed
        // together or the demo is a set of empty screens.
        val pipeline = suppliers.list(ops)
        assertTrue(pipeline.isNotEmpty(), "the pipeline must have suppliers in it")
    }
}