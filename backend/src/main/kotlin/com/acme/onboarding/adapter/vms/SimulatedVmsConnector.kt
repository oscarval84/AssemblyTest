package com.acme.onboarding.adapter.vms

import com.acme.onboarding.application.vms.OnboardingUpdate
import com.acme.onboarding.application.vms.VmsAssignment
import com.acme.onboarding.application.vms.VmsConnector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A VMS that does not exist, behaving the way the real ones do.
 *
 * The point of shipping this rather than nothing is that the whole loop runs in
 * front of an evaluator: an assignment appears in the VMS, onboarding starts by
 * itself, documents are reviewed and approved, and the outcome lands back on the
 * VMS record. What that demonstrates is the contract, the automation and the
 * reliability machinery — not any particular vendor's API, and the difference is
 * worth stating out loud.
 *
 * The seeded assignments are chosen to exercise both inbound paths. One is for a
 * supplier Acme already knows, engaged on a *second* program: no new company, no
 * second invitation, and a checklist that is mostly already satisfied. That is
 * the path the integration will spend most of its life on, and the one where a
 * naive implementation quietly creates duplicate suppliers.
 */
@Component
class SimulatedVmsConnector(private val clock: Clock) : VmsConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    override val systemName = "SIMULATED_VMS"

    /** What the far side has received, for tests and for the demo narrative. */
    val inbox: MutableList<OnboardingUpdate> = CopyOnWriteArrayList()

    /**
     * Set by tests to exercise the retry and dead-letter path. A demo should see
     * the happy path; the unhappy one is proven by the suite, not by a broken
     * screen.
     */
    @Volatile
    var failNextPushes: Int = 0

    override fun fetchPendingAssignments(since: Instant): List<VmsAssignment> {
        val now = Instant.now(clock)
        val today = LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC))

        return listOf(
            // A supplier Acme already onboarded, engaged on a third program. The
            // legal name differs from the approved W-9 on purpose: one of the two
            // systems is wrong, and v1 flags it rather than picking a winner.
            VmsAssignment(
                externalAssignmentId = "VMS-ASN-55010",
                externalSupplierId = "VMS-SUP-4471",
                supplierLegalName = "Northwind Staffing LLC",
                contactName = "Erin Walsh",
                contactEmail = "erin.walsh@northwind-staffing.example",
                programCode = "ATLAS_LOGISTICS",
                startsOn = today.plusDays(21),
                raisedAt = now,
            ),
            // Nobody has heard of this one: it becomes a supplier, an enrollment
            // and a single invitation, with no ops action anywhere.
            VmsAssignment(
                externalAssignmentId = "VMS-ASN-55011",
                externalSupplierId = "VMS-SUP-8802",
                supplierLegalName = "Vantage Field Solutions",
                contactName = "Priya Nadar",
                contactEmail = "priya.nadar@vantagefield.example",
                programCode = "NORTHSTAR_HEALTH",
                startsOn = today.plusDays(30),
                raisedAt = now,
            ),
        )
    }

    override fun publishOnboardingUpdate(update: OnboardingUpdate) {
        if (failNextPushes > 0) {
            failNextPushes--
            throw IllegalStateException("Simulated VMS rejected the update: upstream timeout")
        }

        inbox.add(update)
        log.info(
            "VMS received {} for supplier {}",
            update.type,
            update.externalSupplierId,
        )
    }
}
