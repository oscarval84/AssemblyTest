package com.acme.onboarding.domain.extraction

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The comparisons a reviewer would otherwise do by eye.
 *
 * These are Acme's rules rather than the model's: the model reads the
 * certificate, and this decides whether what it read is a problem. Keeping them
 * separate is what makes them testable without a network, and what stops a
 * finding changing meaning because a prompt changed.
 *
 * The case that earns its keep is the last one. A flag that fires on
 * "Northwind Staffing Partners" versus "Northwind Staffing Partners, LLC" would
 * be technically correct and would train a reviewer to click past every name
 * mismatch — including the one that mattered.
 */
class CertificateFindingsTest {

    @Test
    fun `a certificate that disagrees with the date on the submission is flagged with both`() {
        val findings = CertificateFindings.evaluate(
            fields = certificate(expiresOn = LocalDate.of(2027, 1, 13)),
            typedExpiry = LocalDate.of(2027, 3, 13),
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )

        val mismatch = findings.single { it.flag == CertificateFlag.EXPIRY_MISMATCH }

        // Both dates, because the reviewer has to decide which is right — and
        // the compliance engine is running on the one that was typed.
        assertTrue(mismatch.detail.contains("2027-01-13"), mismatch.detail)
        assertTrue(mismatch.detail.contains("2027-03-13"), mismatch.detail)
    }

    @Test
    fun `matching dates produce no finding at all`() {
        val findings = CertificateFindings.evaluate(
            fields = certificate(expiresOn = LocalDate.of(2027, 3, 13)),
            typedExpiry = LocalDate.of(2027, 3, 13),
            requiredAggregate = 2_000_000,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `coverage below the program minimum is quoted with both numbers`() {
        val findings = CertificateFindings.evaluate(
            fields = certificate(aggregate = 1_000_000),
            typedExpiry = EXPIRY,
            requiredAggregate = 2_000_000,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )

        val coverage = findings.single { it.flag == CertificateFlag.COVERAGE_BELOW_MINIMUM }

        // The wording a supplier can act on: what the document shows, and what
        // the program requires. Not "coverage appears insufficient".
        assertTrue(coverage.detail.contains("USD 1,000,000"), coverage.detail)
        assertTrue(coverage.detail.contains("USD 2,000,000"), coverage.detail)
    }

    @Test
    fun `a certificate that is already expired says so, and one expiring this week says that`() {
        val expired = CertificateFindings.evaluate(
            fields = certificate(expiresOn = TODAY.minusDays(1)),
            typedExpiry = TODAY.minusDays(1),
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )
        assertTrue(expired.any { it.flag == CertificateFlag.EXPIRED_ON_ARRIVAL })

        val soon = CertificateFindings.evaluate(
            fields = certificate(expiresOn = TODAY.plusDays(3)),
            typedExpiry = TODAY.plusDays(3),
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )
        val finding = soon.single { it.flag == CertificateFlag.EXPIRED_ON_ARRIVAL }
        assertTrue(finding.detail.contains("inside a week"), finding.detail)
    }

    @Test
    fun `a missing field is never a finding`() {
        // A cropped scan and a compliant certificate must not look the same, but
        // neither may a gap be reported as a failure: null means the document
        // does not say, and the reviewer reads it themselves.
        val findings = CertificateFindings.evaluate(
            fields = ExtractedCertificate(),
            typedExpiry = EXPIRY,
            requiredAggregate = 2_000_000,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `a legal suffix is not a name mismatch, and a different company is`() {
        val suffix = CertificateFindings.evaluate(
            fields = certificate(namedInsured = "Northwind Staffing Partners, LLC"),
            typedExpiry = EXPIRY,
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )
        assertFalse(
            suffix.any { it.flag == CertificateFlag.NAME_MISMATCH },
            "an insurer writing the LLC suffix is the same company; flagging it trains reviewers to ignore the flag",
        )

        val different = CertificateFindings.evaluate(
            fields = certificate(namedInsured = "Beacon Technical Services"),
            typedExpiry = EXPIRY,
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )
        val mismatch = different.single { it.flag == CertificateFlag.NAME_MISMATCH }
        assertTrue(mismatch.detail.contains("Beacon Technical Services"), mismatch.detail)
    }

    @Test
    fun `Acme not being the certificate holder is flagged, whatever the wording around it`() {
        val findings = CertificateFindings.evaluate(
            fields = certificate(holder = "Globex Corporation, 1 Main Street"),
            typedExpiry = EXPIRY,
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )
        assertTrue(findings.any { it.flag == CertificateFlag.HOLDER_NOT_ACME })

        val addressed = CertificateFindings.evaluate(
            fields = certificate(holder = "ACME INC., 400 Market Street, Boston MA 02108"),
            typedExpiry = EXPIRY,
            requiredAggregate = null,
            supplierLegalName = "Northwind Staffing Partners",
            today = TODAY,
        )
        assertFalse(addressed.any { it.flag == CertificateFlag.HOLDER_NOT_ACME })
    }

    private fun certificate(
        namedInsured: String? = "Northwind Staffing Partners",
        holder: String? = "Acme Inc., 400 Market Street, Boston MA",
        aggregate: Long? = 2_000_000,
        expiresOn: LocalDate? = EXPIRY,
    ) = ExtractedCertificate(
        namedInsured = namedInsured,
        certificateHolder = holder,
        generalLiabilityAggregate = aggregate,
        expiresOn = expiresOn,
        workersCompensationPresent = true,
        signed = true,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 14)
        val EXPIRY: LocalDate = LocalDate.of(2027, 3, 13)
    }
}
