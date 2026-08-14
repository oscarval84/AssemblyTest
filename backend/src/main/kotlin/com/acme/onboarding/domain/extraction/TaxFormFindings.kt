package com.acme.onboarding.domain.extraction

/**
 * The fields of a W-9 that this system will look at.
 *
 * **The taxpayer identification number is deliberately absent, and that is the
 * point of this type.** Whether the document is transmitted to a processor is
 * Acme's decision, and they can make it. Whether this system keeps a second copy
 * of a taxpayer ID — outside the encrypted column built for it, in a JSON blob
 * on an extraction row — is our decision, and the answer is no. There is nothing
 * to read the number into, so no configuration can cause it to be stored.
 */
data class ExtractedTaxForm(
    val legalName: String? = null,
    val businessName: String? = null,
    /** Line 3 as printed: "C Corporation", "Individual/sole proprietor", "LLC". */
    val taxClassification: String? = null,
    val address: String? = null,
    val signed: Boolean? = null,
)

/**
 * Compares a W-9 against the supplier's own profile.
 *
 * A short list, because a W-9 is a short document: it carries no dates, no
 * thresholds and nothing that drifts. What it can be wrong about is *who it is
 * for* — the name and entity type a supplier typed into their profile, against
 * the ones on the form they sent, which is what Acme files with the IRS.
 *
 * There is no check against the supplier's tax ID here, and there cannot be: the
 * number is never read off the form. Comparing it would mean keeping it.
 *
 * **Nothing here decides anything.** A finding is shown to a reviewer beside the
 * document; no status moves on its own.
 */
object TaxFormFindings {

    fun evaluate(
        fields: ExtractedTaxForm,
        supplierLegalName: String,
        supplierEntityType: String?,
    ): List<ExtractionFinding> = buildList {
        if (fields.legalName != null && !CompanyNames.match(fields.legalName, supplierLegalName)) {
            add(
                ExtractionFinding(
                    ExtractionFlag.NAME_MISMATCH,
                    "The W-9 is filed as \"${fields.legalName}\"; the supplier record says " +
                        "\"$supplierLegalName\". The name on the form is the one Acme files with the IRS.",
                ),
            )
        }

        if (fields.taxClassification != null && supplierEntityType != null &&
            contradicts(fields.taxClassification, supplierEntityType)
        ) {
            add(
                ExtractionFinding(
                    ExtractionFlag.ENTITY_TYPE_MISMATCH,
                    "The W-9 is checked as \"${fields.taxClassification}\"; the profile says " +
                        "\"$supplierEntityType\". One of the two is out of date.",
                ),
            )
        }

        if (fields.signed == false) {
            add(
                ExtractionFinding(
                    ExtractionFlag.NOT_SIGNED,
                    "The certification block is not signed. An unsigned W-9 is not a W-9.",
                ),
            )
        }
    }

    /**
     * Whether the form's line 3 and the profile's entity type cannot both be true.
     *
     * Not string equality, because the two vocabularies genuinely differ: the
     * profile offers "LLC" and the W-9's first checkbox reads "Individual/sole
     * proprietor or single-member LLC" — one box covering two kinds. So each side
     * maps to the *set* of kinds its wording admits, and only disjoint sets are a
     * contradiction. An unrecognised wording on either side yields an empty set
     * and flags nothing: a reviewer has both strings on screen, and a flag they
     * learn to dismiss is worse than no flag.
     */
    private fun contradicts(onForm: String, onProfile: String): Boolean {
        val form = kinds(onForm)
        val profile = kinds(onProfile)
        return form.isNotEmpty() && profile.isNotEmpty() && form.intersect(profile).isEmpty()
    }

    private enum class EntityKind {
        SOLE_PROPRIETOR, PARTNERSHIP, LLC, S_CORPORATION, C_CORPORATION, TRUST_OR_ESTATE, NON_PROFIT
    }

    /**
     * Every kind the wording admits, not the first one matched — "Limited
     * liability company (S corporation)" is both, and so is the combined
     * sole-proprietor box.
     */
    private fun kinds(value: String): Set<EntityKind> {
        val text = value.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

        return buildSet {
            if ("sole propriet" in text || "individual" in text) add(EntityKind.SOLE_PROPRIETOR)
            if ("partnership" in text) add(EntityKind.PARTNERSHIP)
            if ("llc" in text || "limited liability" in text) add(EntityKind.LLC)
            if ("s corp" in text) add(EntityKind.S_CORPORATION)
            if ("c corp" in text) add(EntityKind.C_CORPORATION)
            if ("trust" in text || "estate" in text) add(EntityKind.TRUST_OR_ESTATE)
            if ("non profit" in text || "nonprofit" in text || "exempt" in text) add(EntityKind.NON_PROFIT)
        }
    }
}
