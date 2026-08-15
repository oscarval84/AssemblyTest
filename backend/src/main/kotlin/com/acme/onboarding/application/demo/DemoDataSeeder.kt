package com.acme.onboarding.application.demo

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.DemoRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.NewSubmission
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRecord
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.adapter.persistence.VmsLinkRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.criteria.CriteriaReviewService
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.document.DocumentStore
import com.acme.onboarding.application.document.SignatureService
import com.acme.onboarding.application.document.SimpleDocumentRenderer
import com.acme.onboarding.application.notification.Notifier
import com.acme.onboarding.application.onboarding.StageProgression
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.compliance.SubmissionStatus
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The demo world.
 *
 * Two things make this a feature rather than a fixture. First, evaluators click
 * everything — including approving and rejecting until the interesting states are
 * gone — so a reset that restores a known world is what keeps the app
 * demonstrable. Second, seeding through the real services rather than through
 * `INSERT` statements means the seeded history is produced by the same code
 * paths a user drives: the audit chain, the outbox and the stage machine are all
 * exercised before anyone signs in.
 */
@Component
class DemoDataSeeder(
    private val demo: DemoRepository,
    private val vmsLinks: VmsLinkRepository,
    private val users: UserRepository,
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val submissions: SubmissionRepository,
    private val supplierService: SupplierService,
    private val signatureService: SignatureService,
    private val invitations: InvitationService,
    private val progression: StageProgression,
    private val notifier: Notifier,
    private val recorder: ActivityRecorder,
    private val criteriaReview: CriteriaReviewService,
    private val store: DocumentStore,
    private val renderer: SimpleDocumentRenderer,
    private val passwordEncoder: PasswordEncoder,
    private val properties: AcmeProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun seedIfEmpty() {
        if (!properties.demo.seedOnStartup) return
        if (!demo.isEmpty()) return
        seed()
        log.info("Seeded the demo world. Every account's password is {}", DEMO_PASSWORD)
    }

    /**
     * Admin-only, and only where demo mode is on. Restores the world an
     * evaluator started from, and records that it happened — the reset is itself
     * an auditable event, because an unexplained gap in a supplier's history is
     * exactly what the audit log exists to prevent.
     */
    @Transactional
    fun reset(actor: Actor) {
        actor.requireAdmin()
        check(properties.demo.seedOnStartup) { "Demo mode is off in this environment" }

        // Rewinding means clearing the activity log, and where the audit-log
        // grants are in force the application cannot — by design, and it is the
        // same restriction the memo describes to Dana. Refusing in words beats
        // a half-finished truncate or a 500.
        if (!demo.canClearOperationalData()) {
            throw InvalidRequestException(
                "The demo data cannot be reset in this environment, and that is the audit log working " +
                    "as promised: rewinding would mean erasing it, and this application's database role " +
                    "is allowed to append to that table and nothing else. To start from a clean world, " +
                    "recreate the database — the demo seeds itself on an empty one.",
            )
        }

        demo.truncateOperationalData()
        seed()

        recorder.record(
            action = AuditAction.DEMO_DATA_RESET,
            subjectType = "SYSTEM",
            subjectId = null,
            actor = null,
            systemActorLabel = "demo reset requested by ${actor.label}",
        )
    }

    private fun seed() {
        val programs = seedPrograms()
        val staff = seedStaff(programs.getValue(NORTHSTAR).first)
        seedCriteria(staff, programs)

        seedNorthwind(staff)
        seedBeacon(staff)
        seedCedarGrove(staff)
        seedLakeside(staff)
        seedHarborPoint(staff)
        seedRidgeline(staff)
    }

    /**
     * The acceptance criteria Marcus would have written, per program.
     *
     * These live here rather than in `V7__acceptance_criteria.sql`, and the
     * reason is a defect that migration could not avoid: it seeded criteria by
     * joining `program_requirement`, and the programs are created *here*, at
     * runtime, long after Flyway has finished. On any freshly migrated database
     * that join matched nothing, so the demo world had no criteria at all — the
     * review dialog said "no acceptance criteria are set" for every document,
     * and the client's own answer 2 was invisible in the product built for it.
     *
     * It only ever appeared to work on the original development database, where
     * programs happened to exist before V7 ran. The same shape as the seeder
     * that silently skipped a clean database, and found the same way: by
     * rehearsing the demo instead of trusting that it would hold.
     *
     * Authored as Marcus, so the audit trail attributes them to a person and
     * `CRITERIA_UPDATED` is in the log where a reviewer would expect it.
     */
    private fun seedCriteria(staff: Staff, programs: Map<String, Pair<UUID, List<String>>>) {
        val requirements = catalog.requirementsForPrograms(programs.values.map { it.first })

        requirements
            .filter { it.documentType.code == "CERTIFICATE_OF_INSURANCE" }
            .forEach { requirement ->
                val minimum = (requirement.constraints["generalLiabilityMinimum"] as? Number)?.toLong()
                    ?: return@forEach

                criteriaReview.author(
                    staff.ops,
                    requirement.id,
                    listOf(
                        "The certificate holder is Acme Inc., 400 Market Street, Boston MA.",
                        "The general liability aggregate is at least USD ${"%,d".format(minimum)}.",
                        "Workers' compensation coverage is present and unexpired.",
                        "The policy expiry date is at least 30 days after today.",
                        "The certificate is signed by an authorised representative of the insurer.",
                    ),
                )
            }
    }

    // -- reference world ------------------------------------------------------

    /** Program id and the requirement set each one imposes. */
    private fun seedPrograms(): Map<String, Pair<UUID, List<String>>> {
        val types = catalog.documentTypes().associateBy { it.code }

        fun program(code: String, name: String, description: String, requirements: List<Pair<String, String>>): Pair<UUID, List<String>> {
            val id = catalog.insertProgram(code, name, description)
            requirements.forEach { (typeCode, constraints) ->
                catalog.addRequirement(id, types.getValue(typeCode).id, constraints)
            }
            return id to requirements.map { it.first }
        }

        return mapOf(
            NORTHSTAR to program(
                NORTHSTAR,
                "Northstar Health System",
                "Clinical and allied health staffing across 14 hospitals.",
                listOf(
                    "W9" to "{}",
                    "CERTIFICATE_OF_INSURANCE" to """{"generalLiabilityMinimum": 2000000, "currency": "USD"}""",
                    "SUPPLIER_AGREEMENT" to "{}",
                    "BACKGROUND_CHECK_ATTESTATION" to """{"renewalMonths": 12}""",
                ),
            ),
            MERIDIAN to program(
                MERIDIAN,
                "Meridian Financial Group",
                "Technology and back-office contractors for a regional bank.",
                listOf(
                    "W9" to "{}",
                    "CERTIFICATE_OF_INSURANCE" to """{"generalLiabilityMinimum": 1000000, "currency": "USD"}""",
                    "SUPPLIER_AGREEMENT" to "{}",
                    "BANKING_FORM" to "{}",
                    "PROGRAM_ADDENDUM" to "{}",
                ),
            ),
            ATLAS to program(
                ATLAS,
                "Atlas Logistics",
                "Warehouse and driver placements across the Midwest.",
                listOf(
                    "W9" to "{}",
                    "CERTIFICATE_OF_INSURANCE" to """{"generalLiabilityMinimum": 1000000, "currency": "USD"}""",
                    "SUPPLIER_AGREEMENT" to "{}",
                    "BANKING_FORM" to "{}",
                ),
            ),
        )
    }

    /**
     * [reviewer] is a second ops account, and it has to exist: the database
     * refuses a submission whose approver is also its uploader, so seeding an
     * approved document that ops uploaded on a supplier's behalf needs two people.
     */
    private data class Staff(val admin: Actor, val ops: Actor, val reviewer: Actor, val programManager: Actor)

    private fun seedStaff(northstarProgramId: UUID): Staff {
        val admin = staffUser("dana.whitfield@acme-msp.example", "Dana Whitfield", Role.ADMIN)
        val ops = staffUser("marcus.lee@acme-msp.example", "Marcus Lee", Role.OPS)
        val reviewer = staffUser("tobi.adeyemi@acme-msp.example", "Tobi Adeyemi", Role.OPS)
        val manager = staffUser("priya.raman@acme-msp.example", "Priya Raman", Role.PROGRAM_MANAGER)
        users.replaceProgramScope(manager.userId, listOf(northstarProgramId))

        return Staff(admin, ops, reviewer, manager)
    }

    private fun staffUser(email: String, fullName: String, role: Role): Actor {
        val id = users.insert(
            email = email,
            fullName = fullName,
            role = role,
            supplierId = null,
            status = UserStatus.ACTIVE,
            passwordHash = passwordEncoder.hash(DEMO_PASSWORD),
        )
        return Actor(id, email, fullName, role, null)
    }

    // -- suppliers ------------------------------------------------------------

    /** Approved, working, and with a certificate about to lapse — the state Dana watches for. */
    private fun seedNorthwind(staff: Staff) {
        val supplier = newSupplier("Northwind Staffing Partners", "Erin Walsh", "erin.walsh@northwind-staffing.example")
        val user = supplierUser(supplier, "erin.walsh@northwind-staffing.example", "Erin Walsh")
        enroll(supplier, staff.ops, NORTHSTAR, MERIDIAN)
        completeProfile(user, supplier, "S Corporation", "412 Beacon Street", "Boston", "MA", "02115", "617-555-0142", "84-2910473")

        approvedDocument(supplier, user, staff.reviewer, "W9", null, null)
        approvedDocument(supplier, user, staff.reviewer, "CERTIFICATE_OF_INSURANCE", null, today().plusDays(18))
        approvedDocument(supplier, user, staff.reviewer, "BANKING_FORM", null, null)
        approvedDocument(supplier, user, staff.reviewer, "BACKGROUND_CHECK_ATTESTATION", enrollmentFor(supplier, NORTHSTAR), today().plusMonths(9))
        sign(user, supplier, "SUPPLIER_AGREEMENT", null)
        sign(user, supplier, "PROGRAM_ADDENDUM", enrollmentFor(supplier, MERIDIAN))

        // Northwind arrived through the VMS, so it carries a link. The next sync
        // finds it already known and creates only the new enrollment — the path
        // the integration spends most of its life on, and the one where a naive
        // implementation quietly creates a duplicate supplier.
        vmsLinks.link("SIMULATED_VMS", "SUPPLIER", supplier.id, "VMS-SUP-4471")

        moveTo(supplier, OnboardingStage.APPROVED, staff.ops)
        enrollments.listForSupplier(supplier.id).forEach { enrollments.activate(it.id) }
        notifier.onboardingCompleted(
            recipientEmail = user.email,
            recipientName = user.fullName,
            supplierId = supplier.id,
            companyName = supplier.legalName,
        )
    }

    /** Everything sent, nothing reviewed: the queue Marcus opens in the morning. */
    private fun seedBeacon(staff: Staff) {
        val supplier = newSupplier("Beacon Technical Services", "Sam Ortiz", "sam.ortiz@beacontech.example")
        val user = supplierUser(supplier, "sam.ortiz@beacontech.example", "Sam Ortiz")
        enroll(supplier, staff.ops, NORTHSTAR)
        completeProfile(user, supplier, "LLC", "88 Cedar Way", "Providence", "RI", "02903", "401-555-0188", "27-4419902")

        pendingDocument(supplier, user, "W9", null, null)

        // The one document in the demo world that argues with itself, and both
        // arguments are ones Marcus has to settle. Sam typed seven months when
        // the policy expires in five — the shape of the error that let a supplier
        // work on lapsed cover twice. And the aggregate is USD 1M against
        // Northstar's USD 2M, which is the rejection the demo script quotes.
        pendingDocument(
            supplier, user, "CERTIFICATE_OF_INSURANCE", null, today().plusMonths(7),
            certificate = CertificateFacts(
                insurer = "Continental Casualty Company",
                policyNumber = "GL-4471902",
                eachOccurrence = 1_000_000,
                aggregate = 1_000_000,
                effectiveOn = today().minusMonths(7),
                printedExpiry = today().plusMonths(5),
            ),
        )
        pendingDocument(supplier, user, "BACKGROUND_CHECK_ATTESTATION", enrollmentFor(supplier, NORTHSTAR), today().plusMonths(11))
        sign(user, supplier, "SUPPLIER_AGREEMENT", null)

        moveTo(supplier, OnboardingStage.IN_REVIEW, staff.ops)
    }

    /** A rejection with a reason, and the email that told them about it. */
    private fun seedCedarGrove(staff: Staff) {
        val supplier = newSupplier("Cedar Grove Consulting", "Jean Pike", "jean.pike@cedargrove.example")
        val user = supplierUser(supplier, "jean.pike@cedargrove.example", "Jean Pike")
        enroll(supplier, staff.ops, MERIDIAN)
        completeProfile(user, supplier, "Sole Proprietorship", "19 Mill Road", "Hartford", "CT", "06103", "860-555-0119", "55-8820134")

        approvedDocument(supplier, user, staff.reviewer, "W9", null, null)
        val rejected = pendingDocument(supplier, user, "CERTIFICATE_OF_INSURANCE", null, today().plusMonths(4))
        submissions.recordReview(
            id = rejected,
            status = SubmissionStatus.REJECTED,
            reviewerId = staff.ops.userId,
            rejectionReasonCode = "ILLEGIBLE",
            rejectionCriterionId = null,
            rejectionNote = "Page 2 is cut off on the right edge — the general liability limit is not readable.",
            at = clock.instant(),
        )
        recorder.record(
            action = AuditAction.DOCUMENT_REJECTED,
            subjectType = "DOCUMENT",
            subjectId = rejected,
            actor = staff.ops,
            supplierId = supplier.id,
            after = mapOf("reason" to "ILLEGIBLE", "documentType" to "CERTIFICATE_OF_INSURANCE"),
        )
        notifier.documentRejected(
            recipientEmail = user.email,
            recipientName = user.fullName,
            supplierId = supplier.id,
            documentName = "Certificate of Insurance",
            reasonLabel = "Illegible or partially cut off",
            note = "Page 2 is cut off on the right edge — the general liability limit is not readable.",
        )
        sign(user, supplier, "SUPPLIER_AGREEMENT", null)

        moveTo(supplier, OnboardingStage.CHANGES_REQUESTED, staff.ops)
    }

    /** Second program on an existing supplier — the reuse the client asked to see. */
    private fun seedLakeside(staff: Staff) {
        val supplier = newSupplier("Lakeside Medical Staffing", "Alicia Moore", "alicia.moore@lakesidemed.example")
        val user = supplierUser(supplier, "alicia.moore@lakesidemed.example", "Alicia Moore")
        enroll(supplier, staff.ops, NORTHSTAR, ATLAS)
        completeProfile(user, supplier, "C Corporation", "2200 Lakeshore Drive", "Chicago", "IL", "60614", "312-555-0177", "36-7741208")

        approvedDocument(supplier, user, staff.reviewer, "W9", null, null)
        approvedDocument(supplier, user, staff.reviewer, "CERTIFICATE_OF_INSURANCE", null, today().plusMonths(5))
        sign(user, supplier, "SUPPLIER_AGREEMENT", null)

        moveTo(supplier, OnboardingStage.DOCUMENTS_IN_PROGRESS, staff.ops)
    }

    /** Signed in, profile untouched. */
    private fun seedHarborPoint(staff: Staff) {
        val supplier = newSupplier("Harbor Point Talent", "Nina Alvarez", "nina.alvarez@harborpoint.example")
        supplierUser(supplier, "nina.alvarez@harborpoint.example", "Nina Alvarez")
        enroll(supplier, staff.ops, MERIDIAN)
        moveTo(supplier, OnboardingStage.REGISTERED, staff.ops)
    }

    /** Invited an hour ago, with a live invitation link sitting in the outbox. */
    private fun seedRidgeline(staff: Staff) {
        supplierService.createAndInvite(
            actor = staff.ops,
            request = NewSupplierRequest(
                legalName = "Ridgeline Field Services",
                contactName = "Chris Dunn",
                contactEmail = "chris.dunn@ridgelinefs.example",
                programIds = listOf(programId(ATLAS)),
            ),
        )
    }

    // -- building blocks ------------------------------------------------------

    private fun newSupplier(legalName: String, contactName: String, contactEmail: String): SupplierRecord {
        val id = suppliers.insert(legalName, contactName, contactEmail)
        return suppliers.findById(id)!!
    }

    private fun supplierUser(supplier: SupplierRecord, email: String, fullName: String): Actor {
        val id = users.insert(
            email = email,
            fullName = fullName,
            role = Role.SUPPLIER_USER,
            supplierId = supplier.id,
            status = UserStatus.ACTIVE,
            passwordHash = passwordEncoder.hash(DEMO_PASSWORD),
        )
        return Actor(id, email, fullName, Role.SUPPLIER_USER, supplier.id)
    }

    private fun enroll(supplier: SupplierRecord, ops: Actor, vararg programCodes: String) {
        programCodes.forEach { code -> supplierService.enroll(ops, supplier.id, programId(code)) }
    }

    private fun completeProfile(
        user: Actor,
        supplier: SupplierRecord,
        entityType: String,
        address: String,
        city: String,
        state: String,
        postalCode: String,
        phone: String,
        taxId: String,
    ) {
        supplierService.updateProfile(
            actor = user,
            supplierId = supplier.id,
            request = ProfileUpdateRequest(
                legalName = supplier.legalName,
                dbaName = null,
                entityType = entityType,
                taxId = taxId,
                addressLine1 = address,
                addressLine2 = null,
                city = city,
                state = state,
                postalCode = postalCode,
                primaryContactName = user.fullName,
                primaryContactEmail = user.email,
                primaryContactPhone = phone,
            ),
        )
    }

    /**
     * What a seeded certificate actually says on its face.
     *
     * These carry real field values rather than a placeholder page, and the
     * reason is that the extraction feature's entire value is *disagreeing* with
     * what a supplier typed. A page reading "this is demo data" gives it nothing
     * to disagree with, so the one screen where the model earns its place shows
     * a row of nulls — correct behaviour, and a demonstration of nothing.
     *
     * [printedExpiry] is therefore separate from the date recorded on the
     * submission. Where the two differ the reviewer sees the disagreement and
     * the one-click correction, which is the whole point of the feature.
     */
    private data class CertificateFacts(
        val insurer: String,
        val policyNumber: String,
        val eachOccurrence: Long,
        val aggregate: Long,
        val effectiveOn: LocalDate,
        val printedExpiry: LocalDate,
    ) {
        companion object {
            /** A certificate saying exactly what the supplier typed, clearing USD 2M. */
            fun agreeingWith(typedExpiry: LocalDate) = CertificateFacts(
                insurer = "Continental Casualty Company",
                policyNumber = "GL-4471902",
                eachOccurrence = 1_000_000,
                aggregate = 2_000_000,
                effectiveOn = typedExpiry.minusYears(1),
                printedExpiry = typedExpiry,
            )
        }
    }

    /**
     * A certificate laid out the way a broker sends one, so the model reads
     * fields rather than prose. Marked SPECIMEN on its face: this is seeded
     * demonstration data and must never be mistaken for cover that exists.
     */
    private fun certificateLines(supplier: SupplierRecord, facts: CertificateFacts): List<String> {
        val money = { amount: Long -> "USD " + "%,d".format(amount) }
        return listOf(
            "SPECIMEN - DEMONSTRATION DATA. THIS IS NOT EVIDENCE OF INSURANCE.",
            "",
            "CERTIFICATE OF LIABILITY INSURANCE",
            "",
            "PRODUCER",
            "  Harbor & Vale Insurance Brokers",
            "  120 Water Street, Providence RI 02903",
            "",
            "INSURED",
            "  " + supplier.legalName,
            "",
            "INSURER A: " + facts.insurer + "   NAIC 20443",
            "",
            "COVERAGES",
            "",
            "TYPE OF INSURANCE: Commercial General Liability",
            "  Policy number:                " + facts.policyNumber,
            "  Policy effective date:        " + facts.effectiveOn,
            "  Policy expiration date:       " + facts.printedExpiry,
            "  Each occurrence:              " + money(facts.eachOccurrence),
            "  General aggregate:            " + money(facts.aggregate),
            "  Products - comp/op aggregate: " + money(facts.aggregate),
            "",
            "WORKERS COMPENSATION AND EMPLOYERS LIABILITY",
            "  Included. Statutory limits.",
            "",
            "CERTIFICATE HOLDER",
            "  Acme Inc.",
            "  400 Market Street, Boston MA 02108",
            "",
            "AUTHORIZED REPRESENTATIVE",
            "  Signed: M. Vale",
        )
    }

    private fun pendingDocument(
        supplier: SupplierRecord,
        uploader: Actor,
        typeCode: String,
        enrollmentId: UUID?,
        expiresOn: LocalDate?,
        certificate: CertificateFacts? = null,
    ): UUID {
        val type = catalog.documentTypeByCode(typeCode)!!
        val pdf = renderer.render(
            title = "${type.name} — ${supplier.legalName}",
            lines = if (typeCode == "CERTIFICATE_OF_INSURANCE" && expiresOn != null) {
                certificateLines(supplier, certificate ?: CertificateFacts.agreeingWith(expiresOn))
            } else {
                listOf(
                    "This is seeded demo data, not a real ${type.name.lowercase()}.",
                    "",
                    "Company: ${supplier.legalName}",
                    "Document type: ${type.name} (${type.code})",
                    "Classification: ${type.classification}",
                    expiresOn?.let { "Expires: $it" } ?: "This document does not expire.",
                )
            },
        )

        val storageKey = "suppliers/${supplier.id}/${typeCode.lowercase()}/${UUID.randomUUID()}.pdf"
        store.put(storageKey, pdf, "application/pdf")

        val version = submissions.supersedeCurrent(supplier.id, type.id, enrollmentId) + 1
        val id = submissions.insert(
            NewSubmission(
                supplierId = supplier.id,
                documentTypeId = type.id,
                enrollmentId = enrollmentId,
                version = version,
                storageKey = storageKey,
                originalFilename = "${typeCode.lowercase()}-${supplier.legalName.lowercase().replace(' ', '-')}.pdf",
                contentType = "application/pdf",
                sizeBytes = pdf.size.toLong(),
                checksumSha256 = sha256(pdf),
                issuedOn = expiresOn?.minusYears(1),
                expiresOn = expiresOn,
                uploadedBy = uploader.userId,
            ),
        )

        recorder.record(
            action = AuditAction.DOCUMENT_UPLOADED,
            subjectType = "DOCUMENT",
            subjectId = id,
            actor = uploader,
            supplierId = supplier.id,
            after = mapOf("documentType" to typeCode, "version" to version, "expiresOn" to expiresOn?.toString()),
        )
        return id
    }

    private fun approvedDocument(
        supplier: SupplierRecord,
        uploader: Actor,
        reviewer: Actor,
        typeCode: String,
        enrollmentId: UUID?,
        expiresOn: LocalDate?,
    ): UUID {
        val id = pendingDocument(supplier, uploader, typeCode, enrollmentId, expiresOn)
        submissions.recordReview(
            id = id,
            status = SubmissionStatus.APPROVED,
            reviewerId = reviewer.userId,
            rejectionReasonCode = null,
            rejectionCriterionId = null,
            rejectionNote = null,
            at = clock.instant(),
        )
        recorder.record(
            action = AuditAction.DOCUMENT_APPROVED,
            subjectType = "DOCUMENT",
            subjectId = id,
            actor = reviewer,
            supplierId = supplier.id,
            after = mapOf("documentType" to typeCode),
        )
        return id
    }

    private fun sign(user: Actor, supplier: SupplierRecord, typeCode: String, enrollmentId: UUID?) {
        signatureService.sign(
            actor = user,
            request = SignatureService.SignRequest(
                supplierId = supplier.id,
                documentTypeCode = typeCode,
                enrollmentId = enrollmentId,
                typedName = user.fullName,
            ),
        )
    }

    private fun moveTo(supplier: SupplierRecord, stage: OnboardingStage, actor: Actor) {
        progression.moveTo(suppliers.findById(supplier.id)!!, stage, actor)
    }

    private fun enrollmentFor(supplier: SupplierRecord, programCode: String): UUID =
        enrollments.listForSupplier(supplier.id).first { it.programCode == programCode }.id

    private fun programId(code: String): UUID = catalog.programByCode(code)!!.id

    private fun today(): LocalDate = LocalDate.now(clock.withZone(properties.businessTimeZone))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One sign-in an evaluator can use, and what it is worth looking at. */
    data class DemoAccount(
        val role: String,
        val name: String,
        val email: String,
        val whatTheySee: String,
    )

    companion object {
        /** Printed at startup and shown on the sign-in screen in demo mode. */
        const val DEMO_PASSWORD = "Onboarding2026!"

        /**
         * The brief asks for two roles. Showing all four costs nothing and
         * answers "does authorization actually work" before anyone asks.
         */
        val DEMO_ACCOUNTS: List<DemoAccount> = listOf(
            DemoAccount(
                role = "Administrator",
                name = "Dana Whitfield",
                email = "dana.whitfield@acme-msp.example",
                whatTheySee = "Everything, plus staff administration and the audit trail.",
            ),
            DemoAccount(
                role = "Supplier operations",
                name = "Marcus Lee",
                email = "marcus.lee@acme-msp.example",
                whatTheySee = "The pipeline and the review queue. Cannot change anyone's access.",
            ),
            DemoAccount(
                role = "Program manager",
                name = "Priya Raman",
                email = "priya.raman@acme-msp.example",
                whatTheySee = "Read-only, and only suppliers in Northstar Health System.",
            ),
            DemoAccount(
                role = "Supplier",
                name = "Alicia Moore, Lakeside Medical Staffing",
                email = "alicia.moore@lakesidemed.example",
                whatTheySee = "Their own portal: two programs, one of them mostly pre-filled.",
            ),
            DemoAccount(
                role = "Supplier",
                name = "Jean Pike, Cedar Grove Consulting",
                email = "jean.pike@cedargrove.example",
                whatTheySee = "A rejected certificate, with the reason and a way to replace it.",
            ),
        )

        private const val NORTHSTAR = "NORTHSTAR_HEALTH"
        private const val MERIDIAN = "MERIDIAN_FINANCIAL"
        private const val ATLAS = "ATLAS_LOGISTICS"
    }
}
