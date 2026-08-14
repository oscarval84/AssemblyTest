# Decision memo — Acme supplier onboarding, v1

**To:** Dana Whitfield, VP Supplier Management
**Re:** What we built, what we chose not to build, and what we need from you
**Scope:** two focused days

---

## The short version

You told us three things went wrong before: onboarding takes three to six weeks, suppliers
experience it as email into a void, and twice a supplier worked on an expired insurance
certificate. This build attacks those three directly and treats everything else as secondary.

What runs end to end today: a supplier is invited, registers, completes a company profile, sees a
per-program checklist of exactly what Acme needs and why, uploads documents, signs the master
agreement, and watches each item move. Marcus reviews from a queue ordered by wait time, accepts or
hands a document back with a reason the supplier reads verbatim, and approving the last outstanding
item completes onboarding and activates the programs. A nightly sweep chases expiring certificates.
Assignments pull in from the VMS and outcomes are written back. Every state change is an event, and
you can hand the resulting history to an auditor as a spreadsheet or as a document.

Two things are deliberately not switched on, and both are a credential rather than a build: email
delivery, and the model — which prefills the review checklist and reads a certificate of insurance to
check what the supplier typed against what the document says. Details below.

---

## Decisions we made, and why

### 1. Status is computed, never stored

A `COMPLIANT` flag written to a row becomes a lie at midnight with nothing having touched the row.
Compliance is derived from the current date, in Acme's business time zone, on every read. The
nightly job exists for the two things a computed value cannot do on its own — tell somebody, and
record that the transition happened.

This is the direct answer to the certificate that lapsed twice. There is no state where the
database says a supplier is compliant and the calendar disagrees.

### 2. The audit log is hash-chained and the application cannot rewrite it

Every event carries the hash of its predecessor, and the application's database role holds only
`INSERT` and `SELECT` on that table. Deleting or altering an event breaks the chain, which makes
tampering *detectable* rather than merely forbidden — a distinction that matters because your
clients audit you, and "we restricted access" is a weaker answer than "here is the check you can
run yourself".

You can run it: choose a supplier on the audit export screen and it walks their chain and reports
whether it is whole. Reads are audited too, not just writes — "who opened this supplier's banking
form, and when" is a question no bucket-level permission can answer.

### 3. Acme writes the acceptance criteria, not us

Asked which three or four reasons your team rejects documents for, you described something better:
let Acme enter the criteria and check submissions against those. A seeded catalog encodes what we
guessed on the day we guessed it; authored criteria encode what you actually require, per program,
maintained by the people who own the requirement, with no deploy.

The payoff is the rejection. A supplier is told *"the general liability aggregate shows USD
1,000,000; this program requires USD 2,000,000"* rather than *"rejected — incorrect information"*.
Three rounds of email is where the three-to-six-week cycle time actually goes.

### 4. The taxpayer ID never leaves the system, and the W-9 leaves only if you say so

Document types carry a classification. A W-9 and banking details are Restricted. The tax ID is
encrypted at rest, only its last four digits are ever rendered, and it is never transmitted to a
third-party model or pushed to the VMS — that part is enforced in code, with no setting attached,
because there is nowhere in the system for a second copy of that number to land.

The *document* is a separate question, and it is yours. Sending a W-9 to a model is off by default
and turned on with one setting. We wrote it that way on purpose. Routing a taxpayer identification
number to an external processor is a governance call rather than an engineering one, you told us a
past vendor was careless with exactly this class of data, and a decision that needs a code change
to act on is not really yours to make. See "What we need from you" below for what we would want in
place first.

### 5. The model advises; a person decides

Where the model is enabled it does two things, and neither of them decides anything.

It **prefills the criteria checklist** — a `PASS`/`FAIL`/`UNCLEAR` per criterion with the span of the
document it relied on. A `FAIL` never rejects and a `PASS` never approves.

And it **reads a document and disagrees out loud**. Your suppliers type the expiry date when they
upload a certificate, the compliance engine runs on that date, and until now nobody checked it
against the document. Extraction compares the two, along with coverage limits against what the
program requires and the named insured against your own record — and when the certificate says
something else, a reviewer sees both dates and applies the correction in one click. It never
rewrites that date on its own: replacing a mistake nobody checks with a mistake nobody can see
would not be an improvement.

The same reading works on a W-9, where the disagreement worth catching is a different one — a form
filed under the owner's other company, which you would otherwise discover when a 1099 goes to the
wrong entity. It is off until you turn it on, for the reason in point 4.

The audit trail keeps what the model said and what the human decided as two separate facts, and
every transmission to the processor is recorded as a disclosure event naming the model.

The honest framing: this saves reading time. It does not save judgement, and the product is
designed to be correct with it switched off.

### 6. Suppliers are deactivated, never deleted

Statutory retention outlasts the relationship. Document *content* can be purged independently of
the record that it existed, was approved by whom, and when — so an erasure request destroys the
file and leaves the chain intact, with a purge event in it.

### 7. Server-side sessions rather than tokens

"Manage access without developer help" means an admin can end somebody's access *now*. A
revocable session row does that; a self-contained token does not until it expires. The cost is a
database read per request, which is the right trade for an internal tool of this size.

---

## What we cut, and what it would take to add

| Cut | Why | To add |
|---|---|---|
| **Automatic extraction on upload** | Certificate extraction is built, but a reviewer runs it. Running it on every upload would transmit every certificate to a processor whether or not anyone was going to read the result — a cost and a disclosure decision that is yours, not ours. | A scheduled job over pending submissions. Hours, once you have decided. |
| **A real e-signature vendor** | Signing produces an executed PDF with the typed name, timestamp, IP, and the hash of the exact template text. That is the artifact an auditor asks for. A vendor adds legal weight, not capability. | A procurement decision first, then an adapter. |
| **Bulk supplier import** | The VMS pull is the import path that matters, and it is built. A spreadsheet importer would compete with it. | Only if suppliers exist outside the VMS in numbers. |
| **SSO / SAML** | You said it comes later. The account model is already role-based and the session layer is where it plugs in. | Entra ID federation; roughly a week including provisioning questions. |
| **Malware scanning on upload** | Uploads are capped at 10 MB, restricted to PDF/PNG/JPEG, and validated by magic bytes rather than by filename. Scanning is a real gap and a documented one, not an oversight. | A scanning service in front of the storage adapter. |
| **A vendor-specific VMS connector** | We do not know which VMS you run. v1 ships the port, the automation, and the reliability machinery against a simulated one. | The vendor's credentials and a field map. The port does not change. |

---

## What we need from you

1. **Which VMS do you run, and who owns the field mapping?** The integration is built to a port. The
   remaining work is a vendor adapter and a decision about which system wins when the VMS's legal
   name and an approved W-9 disagree. Today we flag the conflict and keep both values, because one
   of them is wrong and a person should decide which.

2. **Does an expired certificate stop a placement?** Today expiry reopens document collection and
   flags the supplier; it does not suspend them. That is a policy question with a VMS answer, and
   we did not want to guess it.

3. **Who signs off on sending documents to a third-party model?** Certificates of insurance carry
   company and policy data and no personal identifiers, and are read wherever the model is
   configured. W-9s carry a taxpayer ID, so they are read only where you have said so: it is one
   setting, `AI_W9_EXTRACTION_ENABLED`, and it is off. We left it a setting rather than a refusal
   in the code precisely because it is your call — a decision you cannot act on without asking us
   for a release is not really yours. Before you turn it on we would want the data processing
   agreement, confirmed retention terms from the vendor, and your compliance function's sign-off,
   not just your word, because this is the class of thing your own clients will ask you about.

   One part of it is not yours to switch, and that is deliberate: the taxpayer ID itself is never
   read off the form. There is no field for it anywhere in that path, so the setting governs
   whether the *document* is sent, never whether the *number* is kept.

4. **Retention periods per document type.** The schema holds the field and it is deliberately
   unset. The correct value is a legal answer your counsel owns, and guessing it would be worse
   than asking.

5. **A mail domain and relay.** Delivery is one credential away (see below).

---

## What is not switched on, and what each needs

**Email delivery.** Every notification is written to a transactional outbox in the same transaction
as the change that caused it, drained by a scheduled job, and visible at `/ops/outbox` with the
message exactly as a recipient would read it. Nothing is delivered, and the product says so rather
than marking messages sent that it never sent. Turning it on is four environment variables and an
SMTP credential on a domain whose SPF and DKIM records name the relay. Any provider works.

**The model.** Both uses of it — the criteria prefill and document extraction — are built, and both
are inert without an API key: the buttons are not offered, a reviewer ticks the checklist, and expiry
dates are typed and validated at upload as they always were. Turning it on is an API key.

**W-9 extraction**, separately, is off whatever the API key says, and stays off until you set
`AI_W9_EXTRACTION_ENABLED=true`. That is question 3 above with a switch attached. Every time a
Restricted document does go to the model, the activity log records the transmission and names the
setting that allowed it — so the trail shows when your decision took effect, not merely that it did.

**Scheduled jobs.** The three endpoints exist and are authenticated. Creating the Cloud Scheduler
jobs needs your GCP project.

---

## What this costs to run

Everything sits inside GCP always-free allowances except Cloud SQL, which is a deliberate paid
choice: Postgres with real constraints and real migrations is the foundation the audit story rests
on, and the alternatives that are free are not that. Three scheduled jobs is exactly the free
allowance, which is why there are three and not five.

The model, when enabled, is priced per document reviewed rather than per supplier, and a
certificate is a small document. It is the one line item that scales with volume; everything else
is flat.

---

## The honest risk list

- **This is a working integration against a simulated VMS, not a proven integration with a vendor.**
  What it demonstrates is the contract, the automation, and the failure handling — retries with
  backoff, dead-lettering, conflict flagging, and every exchange visible at `/ops/integrations`.
- **Two days of hardening is two days.** The security posture is deliberate — CSRF on a cookie
  credential, per-request authorization in the application layer, encrypted Restricted fields,
  audited reads — but it has not been through a penetration test.
- **The demo world is seeded data.** It is realistic on purpose (a supplier mid-flow, a rejected
  certificate with a reason, one expiring soon) and it is not production data.
- **Nobody has used this daily yet.** Marcus's queue is ordered by wait time because that is the
  number his team is measured on; whether that is the right default survives contact with his team
  or it does not.
