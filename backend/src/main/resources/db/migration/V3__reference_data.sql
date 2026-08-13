-- Reference data the system needs to function, as distinct from demo data.
-- Demo suppliers, programs and history are seeded separately and are resettable;
-- everything here is part of the product.

-- ---------------------------------------------------------------------------
-- Document types
-- ---------------------------------------------------------------------------
--
-- Scope is the load-bearing column. SUPPLIER-scope documents are satisfied once
-- and reused across every program the supplier joins, so onboarding into a
-- second program does not mean re-uploading a W-9. PROGRAM-scope documents are
-- collected per enrollment.
--
-- retention_months is deliberately left NULL. The correct value is a legal
-- answer Acme's counsel owns, and guessing it would be worse than asking.

INSERT INTO document_type
    (code, name, description, scope, expiring, classification, requires_signature)
VALUES
    ('W9',
     'Form W-9',
     'IRS Form W-9, Request for Taxpayer Identification Number and Certification.',
     'SUPPLIER', FALSE, 'RESTRICTED', FALSE),

    ('CERTIFICATE_OF_INSURANCE',
     'Certificate of Insurance',
     'Proof of active coverage. The expiry date drives compliance status.',
     'SUPPLIER', TRUE, 'CONFIDENTIAL', FALSE),

    ('BANKING_FORM',
     'Banking Details',
     'ACH remittance details. The account number is masked after submission.',
     'SUPPLIER', FALSE, 'RESTRICTED', FALSE),

    ('SUPPLIER_AGREEMENT',
     'Supplier Agreement',
     'Master agreement between Acme and the supplier. Signed in-product.',
     'SUPPLIER', FALSE, 'CONFIDENTIAL', TRUE),

    ('PROGRAM_ADDENDUM',
     'Program Addendum',
     'Program-specific terms, signed once per program the supplier joins.',
     'PROGRAM', FALSE, 'CONFIDENTIAL', TRUE),

    ('BACKGROUND_CHECK_ATTESTATION',
     'Background Check Attestation',
     'Attestation that contractors placed into this program have been screened.',
     'PROGRAM', TRUE, 'CONFIDENTIAL', FALSE);

-- ---------------------------------------------------------------------------
-- Rejection reasons
-- ---------------------------------------------------------------------------
--
-- Marcus was asked which reasons his team actually uses today; these are
-- plausible defaults standing in until he answers, so review is one click
-- rather than a blank text box. A free-text note remains available alongside.

INSERT INTO rejection_reason (code, label, sort_order) VALUES
    ('ILLEGIBLE',             'Illegible or partially cut off',              10),
    ('EXPIRED',               'Already expired on submission',               20),
    ('WRONG_DOCUMENT',        'Wrong document type submitted',               30),
    ('NAME_MISMATCH',         'Legal name does not match the company profile', 40),
    ('INSUFFICIENT_COVERAGE', 'Coverage limits below the program minimum',   50),
    ('MISSING_SIGNATURE',     'Missing a required signature',                60),
    ('INCOMPLETE',            'Required fields are blank',                   70),
    ('OTHER',                 'Other (see note)',                            99);
