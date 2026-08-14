# Architecture — Acme Supplier Onboarding v1

> Language rule: this repository is English-only. See [CLAUDE.md](../CLAUDE.md), Rule #0.

## 1. Shape of the system

Two independently deployable services in one repository, plus a managed database and object store on Google Cloud.

```
                    ┌──────────────────────────────┐
   supplier.acme →  │  Firebase Hosting (CDN)      │
   ops.acme     →   │  React SPA (static assets)   │
                    │                              │
                    │  rewrite /api/**  ───────────┼──► Cloud Run ◄─────────► VMS
                    └──────────────────────────────┘      Kotlin + Spring Boot   pull assignments in,
                                                            │                    write outcomes back
                                    ┌───────────────────────┼───────────────────────┐   (§5)
                                    ▼                       ▼                       ▼
                          Cloud SQL (Postgres)     Cloud Storage (private)   Secret Manager
                          onboarding evidence      documents, V4 signed URLs  credentials

                          Cloud Scheduler ──► POST /internal/jobs/*  (OIDC-authenticated)
                          outbox drain, compliance sweep, VMS sync
```

**The VMS is upstream and downstream.** Onboarding is kicked off by an assignment appearing in Acme's VMS, and the result is written back to it — the client called this critically important, and it is the one integration in v1. Cloud SQL is the system of record for *onboarding evidence*; the VMS remains the system of record for the supplier relationship. §5 draws that line precisely, because a two-system setup fails when both believe they own the same field.

**Why Firebase Hosting in front of Cloud Run.** Hosting rewrites `/api/**` to the backend service, so the browser only ever talks to one origin. That removes CORS entirely and lets the session cookie stay `HttpOnly; Secure; SameSite=Lax` — the simplest secure configuration, and the right one for a system holding tax and banking documents.

**It costs one constraint, and it is not obvious.** Hosting strips every incoming cookie except one named `__session` before proxying, so that it can cache responses. A session cookie under any other name is held by the browser, sent by the browser, and never seen by the backend: sign-in returns 200 and the next request returns 401. The cookie name is therefore configuration rather than a constant — `SESSION_COOKIE_NAME`, set to `__session` in every environment behind Hosting. See [deployment.md](deployment.md).

## 2. Repository layout

```
AssemblyTest/
├── CLAUDE.md                 # project guide + language rule
├── docs/
│   ├── architecture.md       # this file
│   ├── decision-memo.md      # deliverable
│   └── demo-script.md        # Loom outline
├── backend/                  # Kotlin 2.3, Spring Boot 4.1, Gradle Kotlin DSL, JDK 21
│   └── src/main/kotlin/com/acme/onboarding/
│       ├── domain/           # entities, state machines, compliance rules — no framework types
│       ├── application/      # use cases, transaction boundaries
│       ├── adapter/
│       │   ├── web/          # REST controllers, DTOs, OpenAPI annotations
│       │   ├── persistence/  # JdbcClient repositories, Flyway migrations
│       │   ├── storage/      # GCS adapter (+ local filesystem adapter for dev)
│       │   ├── mail/         # outbox writer + optional real transport
│       │   ├── vms/          # VmsConnector port + simulated adapter (§5)
│       │   └── ai/           # Anthropic client: extraction + criteria evaluation
│       └── config/           # security, beans, properties
└── frontend/                 # React 19, Vite, TypeScript, MUI
    └── src/
        ├── api/              # generated TypeScript client (from OpenAPI)
        ├── features/
        │   ├── supplier/     # external portal
        │   ├── ops/          # internal console
        │   └── auth/
        ├── theme/            # MUI theme: palette, typography, shape, density
        └── components/       # composed app components built on MUI primitives
```

**Contract between the two.** The backend publishes OpenAPI 3 via `springdoc`. The frontend generates its types and fetch client from that spec (`openapi-typescript` + `openapi-fetch`) as a build step. This is the mitigation for splitting the stack: a backend change that breaks the contract fails the frontend type-check instead of failing in the browser.

### One application or two

The supplier portal is external and carries Acme's brand; the ops console is internal. They could be two builds. **v1 ships one Vite application with two route groups**, separate layouts and theming, and route-based code splitting so the ops bundle never reaches a supplier's browser.

**This is a demo-scoped decision, and it is the right one only because this is a demo.** Within a two-day build, a second application duplicates the auth layer, the generated API client, the design system and the deploy configuration, and buys nothing a reviewer can see. Branding divergence is a layout and theme concern, not an application boundary.

**For production the calculus flips, and the reason is blast radius rather than bundle size.** The supplier portal faces hundreds of external companies and is the brand surface Dana would show her clients; the ops console serves fifteen internal people. Those have different release cadences and very different tolerances for a bad deploy, and one build means a broken ops release can take the external portal down with it. Splitting also lets the public surface be locked down harder — narrower CSP, tighter rate limits, its own WAF rules — without those constraints getting in ops' way.

The migration is not painful, which is what makes deferring it defensible: the route groups are already separate, the design system is already shared, and the generated API client works unchanged in both. Splitting is extracting a directory into a second Vite config, not a rewrite. The memo records this as a deliberate trade with a known exit, not an oversight.

## 3. Technology decisions

| Concern | Choice | Reasoning |
|---|---|---|
| Backend language | **Kotlin 2.3 on JDK 21** | Null-safety and data classes remove most of the boilerplate that makes Java slow to write, with the entire Spring ecosystem intact. JDK 21 is pinned through the Gradle toolchain, so the build does not depend on whichever JDK happens to be on the machine. |
| Framework | **Spring Boot 4.1** (WebMVC, Security, Validation, Flyway, Actuator) | Batteries included for exactly the things v1 needs: auth, validation, transactions, scheduling. Note this is Boot 4, not 3.x — starter coordinates differ (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`) and Jackson moved to the `tools.jackson` namespace. |
| Build | **Gradle (Kotlin DSL)** + **Jib** | Jib builds the container image without a Dockerfile and pushes straight to Artifact Registry. |
| Migrations | **Flyway** | Versioned SQL. The schema is a reviewable artifact, which matters for an auditable system. |
| Data access | **`JdbcClient`**, hand-written SQL | Chosen over JPA after the domain took shape, for two reasons that both come from what this system is. The read paths are aggregations across enrollments, requirements and submissions, which an ORM expresses as projections over hand-written queries anyway; and the audit log needs SQL an ORM does not offer — an advisory lock per chain, and a payload that must round-trip byte-for-byte. Explicit SQL alongside versioned migrations also keeps the schema the single reviewable description of the data, which is the property an auditor cares about. The cost is mapping code, and it is small at this size. |
| Database | **Cloud SQL for PostgreSQL** | Managed Postgres on GCP, connected from Cloud Run through the Cloud SQL Java connector — IAM authentication, no password in transit. The one deliberately paid component; see § Cost and free-tier budget. |
| Object storage | **Cloud Storage**, private bucket | Uploads go through the backend (validation + audit event); downloads are served as short-lived V4 signed URLs. Documents are never publicly addressable. |
| Frontend | **React 19 + Vite + TypeScript (strict)** — one app, two route groups | Fast build, no framework ceremony. The supplier portal and ops console share one build with separate layouts and theming, split by route. See § One application or two. |
| PDF rendering | **PDFBox** | Generates the executed agreement artifact at signing time (§4). |
| Data fetching | **TanStack Query** | Cache invalidation on mutation is what keeps ops and supplier views showing the same truth. |
| Forms | **react-hook-form + zod** | Client-side validation mirrors the backend contract. |
| UI | **MUI (Material Design)**, themed | A complete, accessible, well-researched design system with the components this product actually needs — data grid, date pickers, form controls — which is real velocity in a two-day build. Shipped under a custom theme rather than stock; see § Design system and theming. |
| Auth | **Server-side sessions** (opaque token in `HttpOnly` cookie, row in Postgres) + BCrypt | Real but simple, and revocation is a `DELETE` — which is what "manage access without developer help" actually requires: an admin demotes someone and it takes effect on their next request, not when a token happens to expire. A stateless JWT cannot do that without a revocation list, which reintroduces the per-request lookup it was meant to avoid. The costs are CSRF protection (§7) and one indexed lookup per request; neither is significant at ~15 internal users. If Acme later wants a mobile client or an API for their enterprise clients, this is the decision to revisit — bearer tokens are the norm there. |
| Email | **Transactional outbox + pluggable transport** | The outbox row is written *in the same transaction* as the state change that caused it, so an email about a rejection cannot exist unless the rejection was committed. A queue (Cloud Tasks, Pub/Sub) enqueued alongside the transaction can fire on a rolled-back change. A scheduled drain delivers; a real transport activates when credentials are present. Everything stays inspectable at `/ops/outbox` — which demos better than mail the evaluators cannot see. |
| Scheduled jobs | **Cloud Scheduler → authenticated endpoints** | Cloud Run scales to zero; an in-process `@Scheduled` job would not run reliably. Scheduler invokes `/internal/jobs/*` with an OIDC token — the outbox drain and the compliance sweep. |
| Secrets | **Secret Manager**, mounted as env vars | No credentials in the repo or in the image. |
| AI extraction | **Anthropic Java SDK** (`com.anthropic:anthropic-java`) | See §8. |
| CI/CD | **GitHub Actions** + Workload Identity Federation | No long-lived service-account keys. |
| Tests | **JUnit 5 + Testcontainers** (backend), **Vitest** (frontend) | Focused on the state machine, the compliance engine, and the requirement resolver — the parts where a bug is invisible until an audit. |

### Design system and theming

MUI gives the product a coherent, accessible foundation and the components this build actually needs — a data grid for the pipeline, date pickers for expiry, a full set of form controls — without assembling them by hand. Accessibility is baked in, which matters for an external portal used by companies ranging from national firms to two-person agencies.

**The one risk to manage: stock Material reads as "a Google product," not as Acme.** The brief evaluates whether an external supplier would associate the experience with Acme's brand, and Dana said she will judge the app by whether she would show it to her clients. A default MUI build undercuts exactly that. This is a solvable problem, but only if it is solved deliberately — the theme is a design decision, not a config file to skip.

Four levers carry almost all of the recognizable "Google" signal, and all four are changed:

| Lever | Default | Here |
|---|---|---|
| **Typeface** | Roboto | Not Roboto. It is the single strongest Google tell — more than color. |
| **Palette** | MUI's default indigo/pink | A restrained enterprise palette, with semantic colors carrying real meaning: compliance state is a color the ops team learns to read at a glance. |
| **Shape** | Material's default radii | Tightened. Rounded-everything reads consumer; this product is a system of record. |
| **Density & elevation** | Airy, shadow-heavy | Compact rows and borders over shadows. An ops lead scanning 300 suppliers needs information density, not whitespace. |

**The two surfaces get different themes from the same component library.** The supplier portal is Acme's brand surface — more generous spacing, warmer, fewer controls per screen, written for someone who does this twice a year. The ops console is a daily tool — dense, keyboard-friendly, more on screen at once. Same primitives, two `ThemeProvider` configurations, which is precisely the split the one-application decision (§2) already anticipated.

**Known cost:** MUI is heavier than a hand-assembled component layer. Route-based code splitting keeps the supplier bundle from carrying the ops-only data grid, which is the largest single piece. At this scale the trade is clearly worth it — the components bought are the ones that would otherwise consume build time better spent on the domain.

### Cost and free-tier budget

Every component runs inside a **permanent free allowance** except one, which is paid deliberately. The allowances used are always-free rather than trial credit — trial credit expires, and an app that dies mid-evaluation is worse than one that was never deployed.

| Component | Free allowance | Fit at demo volume |
|---|---|---|
| Cloud Run | 2M requests, 180k vCPU-seconds, 360k GiB-seconds / month | Far inside. Scales to zero between sessions. |
| Firebase Hosting | 10 GB stored, 360 MB/day transfer | A built SPA is a few hundred KB. |
| Cloud Storage | 5 GB stored, 5k Class A + 50k Class B ops / month | Seeded PDFs are a few MB. |
| Secret Manager | 6 active secret versions, 10k access ops / month | Four secrets, read at cold start. |
| Cloud Scheduler | 3 jobs | All three are now used: outbox drain, compliance sweep, VMS sync (§5). The integration outbox drains on the existing email job rather than claiming a fourth. |
| Artifact Registry | 0.5 GB | One JVM image; prune old tags. |
| Cloud Logging | 50 GB / month | Far inside. |

**The one paid component: Cloud SQL.** It has no always-free tier — the smallest shared-core instance is roughly USD 9–10/month, which prorates to well under a dollar across an assessment window, and the instance is torn down after the evaluation. That is a deliberate trade: a free Postgres alternative (Neon, Supabase) was considered and rejected because managed Cloud SQL with IAM authentication is the stronger architecture and the honest production answer. Paying cents to avoid designing around a constraint that would not exist in production is the right call — and if it ever needs to be free, the swap is a JDBC URL, with no schema, query or application change.

**Two other non-zero costs, stated plainly rather than buried:**

- **Anthropic API usage** for COI extraction — runtime cost per document, separate from the Claude Code build budget. At demo volume it is cents, but it is the one component that scales with real traffic, so the memo names it.
- **CMEK via Cloud KMS** carries a small per-key-version charge. It is already listed in §7 as production configuration rather than v1, so the pilot does not incur it.

**Managing the cold start.** Cloud Run scales to zero, so the first request after a quiet period pays a wake-up penalty — a poor first impression when evaluators drive the app themselves. The scheduled outbox drain runs on a short interval and doubles as a keep-warm ping. The vCPU-seconds this consumes are a rounding error against the free allowance, and it needs no extra job: the drain has to exist regardless.

**Teardown.** The instance is deleted after submission. The memo says so, so that a reviewer returning to a dead URL months later reads it as planned cleanup rather than neglect.

## 4. Domain model

The three client questions are still open. The model below encodes the stated working assumptions and is deliberately shaped so that a different answer changes configuration, not structure.

```
Supplier ──1:N── ProgramEnrollment ──N:1── Program
   │                     │                    │
   │                     │                    └── ProgramRequirement (adds/overrides)
   │                     │                            ├── ReferenceDocument   (optional exemplar)
   │                     │                            └── AcceptanceCriterion (ordered, versioned)
   │                     ├── ComplianceStatus (evaluated per enrollment)
   │                     └── VmsLink (external assignment)
   │
   ├── VmsLink (external supplier)
   ├── DocumentRequirement (resolved: supplier-level ∪ program-level)
   │        └── DocumentSubmission (versioned) ── SignatureRecord (agreements)
   │                     └── CriteriaEvaluation (per criterion, advisory)
   ├── User (supplier role)
   └── ActivityEvent (append-only)
```

**Core entities**

- `Supplier` — one record per company. Legal name, DBA, tax ID (masked after submission), addresses, contacts, entity type, onboarding stage.
- `Program` — an Acme client program. Owns a requirement template.
- `ProgramEnrollment` — the join. **Compliance is evaluated here, not on the supplier**, so a supplier can be compliant for one program and blocked for another.
- `DocumentType` — `W9`, `CERTIFICATE_OF_INSURANCE`, `BANKING_FORM`, `SUPPLIER_AGREEMENT`, plus program-specific types. Carries `scope` (`SUPPLIER` or `PROGRAM`), `expiring` (boolean), `classification` (§7) and `retentionPeriod`. Handling rules read from the type, so adding a document type cannot accidentally create an unclassified one.
- `DocumentRequirement` — the resolved obligation for a given supplier/enrollment.
- `DocumentSubmission` — an uploaded file version. `status ∈ {PENDING, APPROVED, REJECTED}`, `rejectionReasonCode`, `rejectionNote`, `issuedOn`, `expiresOn`, `storageKey`, `checksumSha256`. Re-uploads create a new version; nothing is overwritten.
- `SignatureRecord` — typed name, signer user, timestamp, IP, user agent, template version, SHA-256 of the signed template, and a link to the generated executed PDF (§ Signature artifact).
- `User` / `Role` — `ADMIN`, `OPS`, `PROGRAM_MANAGER` (read-only), `SUPPLIER_USER`.
- `Invitation` — single-use token, expiry, target role, optional supplier binding.
- `ActivityEvent` — append-only audit record: actor, action, subject, before/after, timestamp, request origin, and the hash of the preceding event in the supplier's chain (§7). Covers document *access* and external-processor disclosure, not only state changes.
- `EmailMessage` — the outbox: template, recipient, subject, rendered body, status, sent timestamp.
- `RejectionReason` — a small seeded catalog (illegible scan, wrong document type, expired on arrival) as the always-available baseline. The primary path is now a failed `AcceptanceCriterion`, which produces a reason in Acme's own words.
- `AcceptanceCriterion` — one authored check on a requirement: ordered, versioned, written in plain English by ops (`"General liability aggregate is at least USD 2,000,000"`). Versioned for the same reason the agreement template is: an auditor asking "what was this judged against" needs the text as it stood on the review date, not as it stands today.
- `ReferenceDocument` — an optional exemplar attached to a requirement, with a flag for whether suppliers may see it. A blank form the supplier downloads removes a dead end; an annotated "what good looks like" for reviewers may be internal.
- `CriteriaEvaluation` — per submission, per criterion: `PASS` / `FAIL` / `UNCLEAR`, the quoted evidence span, confidence, model version, and the criteria version evaluated against.
- `ExtractionResult` — AI output for a submission: extracted fields, confidence, and any mismatch flags against the profile.
- `VmsLink` — maps a local `Supplier` or `ProgramEnrollment` to its identifier in the external VMS. The idempotency key for both directions of sync (§5).
- `IntegrationMessage` — the integration outbox: direction, payload, target system, attempt count, status, last error. Same transactional guarantee as `EmailMessage`, same visible log.

**Settled by the client (§12)**

1. **One supplier record; core documents are reusable.** Confirmed. W-9, COI and banking are supplier-level and satisfy every program at once. Program-specific requirements layer on top. Compliance is evaluated per enrollment. The client's framing — *a dashboard of what is already on file, plus whatever is net-new for the program* — is the supplier-facing shape of exactly this model. All suppliers are US-based for v1 (W-9, ACH).
2. **Document review is driven by authored acceptance criteria.** Each requirement carries an optional reference document and an ordered list of acceptance criteria that Acme writes. Submissions are evaluated against those criteria, and a failed criterion becomes the rejection reason. See §8 § Criteria-based review.
3. **The VMS is upstream and downstream of this tool.** Assignments pull in from the VMS and start onboarding; the outcome is written back. See §5.

**Still assumed, not yet answered** — the two sub-questions the client's replies did not reach:

4. **Compliance is binary, with a warning band.** An enrollment is `COMPLIANT` only when every required document is approved and unexpired. `EXPIRING_SOON` is a warning state at ≤30 days to expiry. An expired COI **flags** the supplier and notifies both them and their program managers; it does not hard-block.
5. **Banking details are stored, masked after submission** (last four visible, full value encrypted at rest). They are not transmitted to the VMS — Restricted data does not leave the system without explicit sign-off (§7).

Each of these is a single configuration point (`DocumentType.scope`, the compliance evaluator's threshold and policy, the masking rule, the writeback field map), so a different answer is a small change rather than a rewrite.

### Dates, expiry and time zone

Getting this wrong produces a supplier flagged as expired who is not, or worse, one silently treated as valid after expiry — a false audit result in a system whose entire purpose is audit defensibility. So the rule is explicit, and it rests on one distinction:

**A document's dates are calendar facts. An audit event's timestamp is an instant.** They are different types and are stored differently.

| Field | Type | Reasoning |
|---|---|---|
| `issuedOn`, `expiresOn` | `DATE` | A date printed on a certificate. "Expires 2026-09-15" is not an instant — it has no time or zone of its own, and storing it as a timestamp invents precision that the source document does not have. |
| `ActivityEvent.occurredAt`, session and token timestamps | `timestamptz` (UTC) | "Marcus approved this at 14:32" is a real moment on the clock. Stored UTC, rendered in the viewer's locale. |

**Evaluation rule.** A document is valid **through the end of the day of `expiresOn` in Acme's business time zone** — `America/New_York`, held in configuration rather than hardcoded, because an MSP that expands changes this and should not need a deploy. The compliance sweep resolves "today" in that same zone, so a job firing at 02:00 UTC does not expire a document a day early. The warning band is 30 calendar days before `expiresOn`.

One consequence worth stating: compliance status is a function of the current date, so it is **computed, never stored**. A stored `COMPLIANT` flag becomes a lie at midnight without anything writing to the row. The scheduled sweep exists to send notifications and record state *transitions* in the audit log — not to keep a status column truthful.

### Requirement resolution

Requirements are identified by `(documentType, scope)` and resolved as follows:

- **Supplier-scope** requirements are satisfied **once, globally**, by one approved and unexpired document. A supplier onboarding into a second program does not re-upload their W-9.
- **Program-scope** requirements are satisfied **per enrollment**.
- When two programs impose different constraints on the same supplier-scope document — program A requires USD 1M general liability, program B requires USD 2M — **the document is shared, and each enrollment states its own constraint against it**. The supplier's checklist is built per enrollment, so the same certificate appears under program A asking for USD 1M and under program B asking for USD 2M.

**Where the numeric constraint is actually enforced, and where it is not.** It is enforced at *review*, by a person: each program requirement carries its own acceptance criteria with that program's minimum written into the text, the model checks the submission against the criteria of the program in question, and a failed criterion becomes a rejection in that program's own words. Field extraction adds a second check, against the strictest program the certificate is held to.

It is **not** enforced by the compliance engine. `ComplianceEvaluator` reads presence, review status and expiry — never `constraints`. An approved certificate counts as satisfied for every program that requires that document type, whatever the number on it says.

That is a deliberate v1 boundary rather than an oversight, and the reason is the shared row: a supplier-scope document has one submission and one status, so it cannot be approved for program A and rejected for program B at the same time. Making the engine enforce thresholds would need a per-enrollment decision on a shared document — a new decision table, a review step that decides per program, and a compliance read that consults it. That is real work with a product question attached (does a shared certificate have to clear the strictest program, or can it be good enough for one and not another?), and it is queued in §12 rather than half-built. What v1 does not do is let a shared document be uploaded twice, which is exactly the friction that has suppliers describing the current process as faxing paperwork into a void.

**What the supplier actually sees.** The client described the second-program experience as *a dashboard with information pre-filled, plus whatever is net-new for that program*, so reuse is a screen, not just a resolver. The supplier portal home lists every program the supplier is enrolled in and splits each checklist in two:

- **Already on file** — reused from the company profile, each item showing what it is, when it was approved and when it expires. Shown, not hidden: a supplier who sees an empty checklist assumes the system lost their paperwork, and a supplier who sees "W-9 — approved 12 Mar 2026, no action needed" learns that this tool remembers them. It is also where an expiring document surfaces before it becomes a problem.
- **Needed for this program** — only the net-new items, each with its reference document and acceptance criteria visible up front.

Profile fields behave the same way: pre-filled from the existing record, editable, with edits flowing back to the one supplier record rather than forking a per-program copy. Where a program needs a field the profile has never collected — a program-specific contact, a site location — it is asked once and stored on the enrollment.

The same split drives the ops side: a supplier joining their second program opens with most of their checklist already green, and Marcus' review queue only ever shows him the delta.

### Signature artifact

The client said every previous attempt died at documents and signatures, so the deliverable here is not a record that someone clicked "I agree" — it is **the executed document**.

On signing, the backend renders a **new PDF**: the agreement template, populated with the supplier's data, with a signature block appended containing the typed name, the signer's identity, the timestamp, the originating IP and user agent, and the SHA-256 of the template that was signed. That artifact is stored as an immutable object and linked from the `SignatureRecord`, which also records the template version — so "which text did they actually agree to" has an answer even after the template changes.

An immutable artifact beats a regenerable one here. An auditor asking for the signed agreement wants a file, not an assurance that one could be reconstructed from a database. Rendering uses PDFBox on the JVM; the artifact is classified Confidential (§7) and its access is audited like any other document.

### Onboarding state machine

```
INVITED → REGISTERED → PROFILE_SUBMITTED → DOCUMENTS_IN_PROGRESS → IN_REVIEW
                                                    ▲                   │
                                                    └── CHANGES_REQUESTED ┘
                                                                        │
                                                                    APPROVED → ACTIVE
                                                                                 │
                                                                          COMPLIANCE_HOLD
```

Every transition writes an `ActivityEvent`. The event log is the supplier timeline in the UI and the auditor export — it is a feature, not plumbing.

**Two transitions are wired to the VMS** (§5). `INVITED` is entered automatically when a sync pulls in a new assignment, so the machine can start without a human. `APPROVED → ACTIVE`, and every later move in or out of `COMPLIANCE_HOLD`, queues a writeback in the same transaction as the transition itself.

**"Blocked on"** is derived, never stored: for each supplier the pipeline computes the first unmet obligation (`waiting on supplier: COI`, `waiting on ops: 2 documents in review`, `waiting on signature`). Stored status fields drift; derived ones cannot.

## 5. VMS integration

The client's answer to question 3 is the one that moved the build: *"Critically important — we want to be able to pull from the VMS (kick off automated onboarding) and push this data back to the VMS (write back to the system of record)."*

That reframes the product. Onboarding stops being something a person starts by sending an invitation and finishes by marking a supplier active. It becomes **a stage inside a longer process that begins and ends somewhere else**. §11 listed downstream VMS integration as a deliberate non-goal; that line is now wrong and has been removed.

### What each system owns

| Domain | System of record | Why |
|---|---|---|
| The commercial relationship — which suppliers exist, which programs they are engaged on, rates and contract terms | **VMS** | It is where the engagement is created and where Acme's clients and program managers already work. |
| Onboarding evidence — documents and their versions, approvals and who gave them, signatures, computed compliance, the audit chain | **This tool** | It is the only system that holds the artifacts, and the audit trail is only defensible if it is unbroken and local (§7). |

§1 calls Cloud SQL "the system of record"; the precise statement is **system of record for onboarding evidence**. The VMS remains the system of record for the supplier relationship itself. Naming the split matters, because the failure mode of a two-system setup is two systems that each believe they are authoritative about the same field.

One rule follows from it: **this tool never invents a record in someone else's system of record.** Suppliers can still be invited manually here — ops needs that path, and not every supplier arrives through the VMS — but a locally-created supplier carries no `VmsLink` and is never pushed as a new VMS entity. It appears in the integration log as unlinked, with an action to link it to an existing VMS record. Creating suppliers in the VMS from here is a decision Acme has not made and this build does not make for them.

### Inbound — the VMS kicks off onboarding

**Trigger.** A supplier–program assignment in the VMS reaches a state that means "needs onboarding."

**Mechanism.** A scheduled pull (`POST /internal/jobs/vms-sync`, Cloud Scheduler + OIDC), with an optional HMAC-verified webhook for VMS platforms that can push. The pull is primary rather than the fallback: it needs no publicly reachable inbound endpoint, it is replayable — yesterday's sync can simply be run again — and it degrades to "late" rather than "lost" when the far side is down.

**What one sync does**, per assignment:

1. Resolve `VmsLink` on the external supplier ID.
2. **Known supplier** → create the `ProgramEnrollment` only. The company profile, W-9, COI and banking are already on file and are reused (§4). This is precisely the pre-filled dashboard the client described in answer 1 — the two answers meet here, and the second-program path is the one the integration exercises most.
3. **Unknown supplier** → create the `Supplier`, the enrollment, and a single-use invitation queued in the outbox.
4. Resolve requirements, set the stage to `INVITED`, write the activity events.

No ops action anywhere in that list. That is what "kick off automated onboarding" has to mean to be worth building: Marcus finds the supplier already in his pipeline, at the right stage, with the right checklist, because the VMS said so.

**Idempotency is the whole game.** Every operation is keyed on the external identifier, so a repeated sync is a no-op — it still writes a line to the integration log, because "we checked and nothing changed" is information an operator needs when they are wondering whether the integration is alive. Re-invitation in particular must never happen twice; a supplier receiving the same invite each morning is how a demo becomes a support ticket.

### Outbound — writing back to the system of record

| Event here | What goes back |
|---|---|
| Supplier activated / onboarding complete | Stage, activation date, the list of satisfied requirements |
| Compliance state change on an enrollment | `COMPLIANT` / `EXPIRING_SOON` / `EXPIRED` / `COMPLIANCE_HOLD`, with effective date |
| COI approved | Insurer, policy number, coverage limits, `expiresOn` — so the VMS shows insurance status without anyone retyping it |
| Profile corrections captured during onboarding | Address, contacts, entity type — sent as **proposed** changes, never as silent overwrites |
| Agreement executed | That it exists, the signed date, the template version, and a reference to the artifact — the file itself only if the VMS accepts documents |

**What is never pushed: tax ID and bank account number.** They are classified Restricted (§7), and Restricted data does not leave this system without explicit sign-off — the same posture that keeps W-9 extraction off unless Acme turns it on (§8). If AP expects this tool to be the source of banking data, that is a governance conversation with a data-processing answer attached, and it is queued in §12.

**Reliability reuses the transactional outbox** that already exists for email, which is most of why this fits in the build at all. The `IntegrationMessage` row is written *in the same transaction* as the state change that caused it, so the VMS cannot be told about an activation that rolled back. A scheduled drain delivers with exponential backoff, and after N attempts the message dead-letters and raises a visible banner in the ops console.

That banner is the point. **A silently failing integration is worse than no integration**, because everyone downstream believes the VMS is current. Dana's audit story requires being able to say when the VMS was told, and by what.

**Everything is inspectable at `/ops/integrations`** — every pull and every push, with direction, status, payload, error and a retry action. Same shape as `/ops/outbox`, for the same reason: an integration the evaluators can watch run beats one they have to take on faith.

Every transmission writes an `ActivityEvent` recording an external disclosure — the same treatment §8 gives the AI processor. Data leaving the system is an audit event regardless of where it goes.

### The connector port, and what v1 actually ships

No VMS has been named, and there are no credentials or sandbox. So the boundary is drawn where it can be defended:

- **`VmsConnector` is a port in the application layer** — `fetchPendingAssignments(since)`, `publishOnboardingUpdate(event)`, and a declarative field map. No vendor types reach the domain.
- **v1 ships one adapter: a simulated VMS.** Seeded external assignments on one side, an inbox showing what we pushed on the other, both drivable from the demo. The full loop runs end to end in front of an evaluator: an assignment appears in the VMS → onboarding starts by itself → documents are reviewed and approved → the outcome lands back on the VMS record.
- **A real connector is a second implementation of the same port**, plus credentials and a field map. Fieldglass, Beeline, VNDLY and Bullhorn differ in API shape, auth and push capability, and that difference lives entirely in the adapter.

Stated plainly, because the distinction matters more than the demo: **this is a working integration against a simulated VMS, not a proven integration with a specific vendor.** What v1 demonstrates is the contract, the automation and the reliability machinery. What it does not demonstrate is any particular vendor's API. Which VMS this is now leads the open questions (§12).

### Conflicts and field ownership

Every mapped field has exactly one owner. VMS-owned fields render read-only here with the source named, so nobody edits a value that the next sync will overwrite.

When an inbound value contradicts local evidence — the VMS says "Northwind Staffing LLC", the approved W-9 says "Northwind Staffing Group LLC" — v1 **does not overwrite either side.** It raises a review flag on the supplier record and leaves both values visible. One of the two is wrong, a human has to decide which, and silent auto-resolution in either direction is how two systems diverge in a way nobody can later reconstruct.

### Cost and scheduling

Cloud Scheduler's always-free allowance is **3 jobs**, and the VMS sync is the third: outbox drain, compliance sweep, VMS sync. Exactly at the allowance, noted here so that a fourth job is a conscious decision rather than a surprise bill. The integration outbox drains on the existing email-drain job rather than adding a fourth.

## 6. API surface (representative)

```
POST   /api/auth/login                      POST /api/auth/logout
GET    /api/auth/session                    (who am I, for the SPA's first paint)
POST   /api/auth/password-reset             POST /api/auth/password-reset/{token}
GET    /api/invitations/{token}             POST /api/invitations/{token}/accept
GET    /api/demo/accounts                   POST /api/demo/reset   (demo mode only)

GET    /api/admin/users                     (Acme staff: role, scope, last login, status)
POST   /api/admin/users/invite              PATCH /api/admin/users/{id}/role
PATCH  /api/admin/users/{id}/programs       POST  /api/admin/users/{id}/deactivate
POST   /api/admin/users/{id}/reactivate     POST  /api/admin/users/{id}/reset-password
GET    /api/admin/users/{id}/access-history

GET    /api/suppliers/{id}/users            (supplier-scoped, ops-operated)
POST   /api/suppliers/{id}/users/invite     POST /api/suppliers/{id}/users/{uid}/deactivate
GET    /api/suppliers                       GET  /api/suppliers/{id}
PATCH  /api/suppliers/{id}/profile
GET    /api/suppliers/{id}/requirements
POST   /api/suppliers/{id}/documents        (multipart upload)
GET    /api/documents/{id}/download         (302 → V4 signed URL, short TTL)
POST   /api/documents/{id}/approve          POST /api/documents/{id}/reject
GET    /api/documents/review-queue          (ops queue, oldest first)
GET    /api/documents/rejection-reasons     (the seeded catalog)
GET    /api/documents/agreement             (the text and its hash, shown before signing)
POST   /api/documents/sign
GET    /api/pipeline                        (ops dashboard, grouped by stage)
GET    /api/compliance/expirations
GET    /api/suppliers/{id}/activity
GET    /api/outbox                          (ops-visible notification log)
GET    /api/audit/export.csv                (auditor export: supplier, program, date range)
GET    /api/audit/chains/{key}/verification  (walks a chain and reports the first break)

GET    /api/programs/{id}/requirements
PUT    /api/programs/{id}/requirements/{rid}/criteria      (ops authors acceptance criteria)
POST   /api/programs/{id}/requirements/{rid}/reference     (upload exemplar or blank template)
GET    /api/documents/{id}/criteria-evaluation             (per-criterion verdicts, advisory)

GET    /api/integrations/messages           (ops-visible: every pull and push, §5)
POST   /api/integrations/messages/{id}/retry
POST   /api/suppliers/{id}/vms-link         (link a locally-created supplier to a VMS record)
POST   /api/vms/webhook                     (optional, HMAC-verified, if the VMS pushes)

POST   /internal/jobs/outbox-drain          (Cloud Scheduler, OIDC only — email + integration)
POST   /internal/jobs/compliance-sweep      (Cloud Scheduler, OIDC only)
POST   /internal/jobs/vms-sync              (Cloud Scheduler, OIDC only)
```

Authorization is enforced in the application layer, not in controllers: a supplier user can only ever resolve their own supplier ID, and a program manager only sees enrollments in their programs.

## 7. Security and governance

Acme is an MSP: their enterprise clients audit *them*. So the bar is not "is this secure enough for Acme" but "would this survive an audit run by Acme's largest client." Dana said she will judge the app by whether she would show it to those clients — that is a governance statement, and it sets the bar for this section.

### Security posture

- Documents are private objects; access requires an authorized request that mints a short-lived signed URL. No public bucket, no guessable path.
- **Every document access is audited**, not just every state change. Signed URLs are minted per request, per user, with a short TTL, and minting writes an event. "Who viewed this supplier's banking form, and when" is a question an auditor will ask, and a bucket-level read grant cannot answer it.
- Tax ID and bank account number are encrypted at rest and masked in every read path after submission.
- Session cookies are `HttpOnly; Secure; SameSite=Lax`; sessions are server-side and revocable. `HttpOnly` is the point: an injected script cannot read the credential, which is not true of the common pattern of holding a token in `localStorage`.
- **CSRF protection is required precisely because auth is cookie-based** — browsers attach cookies automatically, so a state-changing request forged by another origin would carry them. Spring Security's CSRF filter is enabled with a cookie-borne token that the SPA echoes in a request header on every mutating call. `SameSite=Lax` is the second layer, not the only one. This is the real cost of choosing sessions over bearer tokens, and it is configuration rather than implementation.
- Passwords are BCrypt-hashed; invitations are single-use and expiring.
- Every state change is audited with actor and timestamp.
- Secrets live in Secret Manager and reach the container as environment variables.
- Uploads are validated on content type and size, and stored under a generated key — never under a client-supplied filename.

### Upload constraints and the malware gap

- **10 MB per file.** Cloud Run's 32 MB HTTP/1 request ceiling is the hard limit; 10 MB is the product decision, since a real W-9 or certificate is under 2 MB and only a badly configured scanner exceeds it. Exceeding it returns a specific message telling the supplier to rescan at a lower resolution — not a generic 413. Suppliers include two-person agencies, and "your file is too large" without a next step is a dead end.
- **PDF, PNG and JPEG only**, validated by inspecting magic bytes rather than trusting the declared `Content-Type`.
- Files are stored under a generated key, so a crafted filename cannot influence the storage path.

**v1 does not scan uploads for malware. This is a known, accepted gap, recorded here rather than left to be discovered.**

The exposure is bounded but real: files are never executed, never served from a domain that could run them, and are delivered only through short-lived signed URLs to authenticated users. What remains is that Acme's ops team downloads supplier-supplied files onto their own machines, so a malicious PDF reaches a human target. That risk is not eliminated by anything in v1.

The production answer is to scan on upload and hold the object unreadable until it passes — on GCP, an event-driven scanner triggered by the object-finalize notification, quarantining on detection. That is roughly a day of work plus a scanner dependency, which is why it is out of a two-day v1 and in the memo instead. The decision memo names it as a gap rather than letting a reviewer assume it was handled.

### Data classification and retention

Every document type carries a classification, and the classification drives handling. This is a column on `DocumentType`, not a policy document nobody reads:

| Class | Contents | Handling |
|---|---|---|
| **Restricted** | W-9 (TIN/SSN), banking form (account number) | Field-level encryption, masked after submission, no external processing unless Acme has explicitly enabled it and never of the number itself (§8), access always audited |
| **Confidential** | Certificate of insurance, supplier agreement | Private storage, signed-URL access, access audited |
| **Internal** | Company profile, contacts, program assignments | Standard access control |

**Retention has to survive a deletion request.** Tax and payment records carry statutory retention obligations that outlast a supplier relationship, so "delete this supplier" can never mean "delete the record." v1 resolves the conflict explicitly:

- Suppliers are **deactivated, never hard-deleted**. The record and its audit trail persist.
- Document *content* can be purged independently of the *record* that it existed, was approved by whom, and when. An erasure request destroys the file in Cloud Storage; the audit chain stays intact and shows a purge event.
- Retention windows are configured per document type rather than hardcoded, because the correct value is a legal answer and we do not have it yet. The memo lists this as an open question for Acme's counsel.

### Audit log integrity

An append-only log is a claim until something enforces it. Three mechanisms:

1. **The application's database role holds only `INSERT` and `SELECT` on `activity_event`**, and a trigger rejects `UPDATE` and `DELETE` for every role including the table's owner. A bug cannot rewrite history, and neither can a SQL injection that reaches this table.

   **What it does not stop, stated precisely.** In the deployed configuration one role both applies the migrations and serves the application, so that role *owns* `activity_event` — and an owner can grant a revoked privilege back to itself, and can disable its own triggers. This was verified rather than assumed: a non-superuser owner is genuinely refused `UPDATE` after the revoke, and is genuinely allowed to re-grant it. So this mechanism stops a bug and an attacker who does not think to run `GRANT` first; it does not stop one who does. Closing it is two roles — a migration owner, and a runtime role that owns nothing — which is deploy configuration rather than a code change, and is recorded in [build-log.md](build-log.md). Mechanism 2 is what holds either way, and it is the one Dana hands to an auditor.
2. **Each event stores the hash of its predecessor**, forming a chain per supplier. Deleting or altering an event breaks the chain, which makes tampering detectable rather than merely forbidden. A verification endpoint walks the chain for auditors.

   One implementation detail is load-bearing and was found by a test rather than by review: the before/after payloads are stored as `json`, not `jsonb`. `jsonb` is a *normalised* representation — it reorders keys and rewrites whitespace — so the text written and the text read back differ, and every event carrying a payload failed its own verification. A log that reports tampering which did not happen is worse than one that reports nothing. `json` preserves the exact bytes, which is what a hash over stored content requires (`V4__audit_state_exact_text.sql`).
3. **Cloud Audit Logs** capture infrastructure-level access separately, outside the application's reach — so someone reaching the database directly still leaves a trace in a system they do not control.

Events record actor, action, subject, before/after, timestamp, and request origin. The auditor-facing export is a first-class feature, not a database dump: filterable by supplier, program and date range, in CSV and PDF. That is literally what Dana asked for — *"a history she can hand to an auditor."*

### Segregation of duties

A four-person ops team cannot run strict four-eyes on everything, so v1 enforces the two rules that actually matter and records the rest:

- **A user cannot approve a document they uploaded.** Ops can upload on a supplier's behalf — a real workflow for the two-person agencies — but that path forces a different approver.
- **Role changes and supplier activation are `ADMIN`-only**, and both write audit events naming the actor.
- Everything else is recorded, not blocked. The log lets a reviewer reconstruct who did what, which is what a segregation-of-duties audit actually tests.

### Access governance

- Least privilege by role, enforced in the application layer: a supplier user can only ever resolve their own supplier ID; a program manager is read-only and scoped to their programs.
- An **access report** lists every active user, role, last login, and program scope — one screen, exportable. Periodic access review is a standard control, and without this it is a spreadsheet exercise.
- Deactivation is immediate and revokes sessions (§ Account lifecycle).
- Invitations expire; dormant accounts surface in the access report rather than being auto-disabled, since auto-disabling ops accounts mid-audit is its own incident.

### Cloud governance

- Separate GCP projects for production and non-production, with distinct service accounts. Demo data never shares a project with real supplier data.
- Cloud Run runs as a **dedicated service account with only the roles it needs** — Cloud SQL client, object read/write on one bucket, secret accessor on named secrets. No default compute service account, no project-level editor.
- **CMEK** on the documents bucket via Cloud KMS, so key rotation and revocation are Acme's lever rather than Google's default.
- Cloud Audit Logs enabled for data access on Cloud Storage and Cloud SQL, exported to a sink the application cannot write to.
- Uniform bucket-level access and public access prevention enforced at the org level, so a misconfigured object ACL cannot make a W-9 public.
- CI/CD authenticates through Workload Identity Federation — no long-lived service account keys anywhere.

**Honest scope note.** Of this list, v1 implements the application-layer controls end to end: classification, access auditing, hash-chained log, segregation of duties, access report, least-privilege service account, and the deactivation path. Project separation, CMEK, org-level constraints and audit-log sinks are configuration that a 2-day build documents and a production rollout applies — the memo says so plainly rather than implying a hardened environment.

### Administration module

Core requirement #4 is that *"an admin can invite users and manage access without developer help."* That is a product surface, not a settings page, and it covers **two populations that must not share a screen**:

| Surface | Manages | Who operates it | Where |
|---|---|---|---|
| **Acme staff administration** | Acme's own people (~15 at full rollout) — ops, program managers, admins | `ADMIN` only | `/ops/admin/users` |
| **Supplier user management** | External users belonging to one supplier | `OPS` and `ADMIN`, scoped to that supplier | Inside the supplier record |

Keeping them separate is what prevents the mistake that matters: an ops user granting Acme-internal access while working a supplier's file. Different screens, different roles, different audit trails.

**The Acme staff screen** is the access report from § Access governance with actions attached — one surface rather than a read-only report plus a separate editor. It lists every internal user with role, program scope, last login and status, and supports: invite (choosing role and, for a program manager, which programs), change role, adjust program scope, deactivate and reactivate, and trigger a password reset on the user's behalf. Every row links to that user's access-change history.

**Four safeguards**, each cheap and each preventing a real incident:

- An admin **cannot remove their own `ADMIN` role** — the classic self-lockout.
- The system **refuses to deactivate or demote the last remaining admin**. An org with zero admins needs a developer, which is the exact thing this feature exists to avoid.
- Role and scope changes take effect on the next request, because sessions are server-side (§ Auth) — a demoted user does not keep elevated access until a token expires.
- Every action writes an audit event naming actor, target, before and after. Access changes are the first thing an auditor samples.

### Account lifecycle

Marcus said it directly: *"We can't file an IT ticket every time we need to add a user or reset a password."* That makes the account lifecycle part of the product, not setup. Four flows, all self-service, all audited:

| Flow | Who starts it | Mechanism |
|---|---|---|
| **Invite** | Admin or ops | Single-use token, 7-day expiry, carries target role and optional supplier binding. Emailed via the outbox. |
| **Self-service reset** | The user, from the login screen | Single-use token, 1-hour expiry, emailed. Requesting a reset for an unknown address returns the same response as a known one — no account enumeration. |
| **Admin-triggered reset** | Admin or ops | Issues the same reset token on the user's behalf, for the phone call that starts with "I can't get in." Ops never sees or sets the password. |
| **Deactivate** | Admin | Flags the user and deletes their session rows. Access ends on the next request, not on the next token refresh. |

Consuming any reset token invalidates every existing session for that user, so a password change is also a "sign out everywhere."

### Alternative considered: Firebase Authentication

Firebase Auth with custom claims was evaluated and rejected. It genuinely wins on the reset flows above — they ship for free instead of costing implementation time — and it removes password storage entirely, which reads well for a system holding tax and banking data. It is also the natural path to the Microsoft SSO the client wants in v2.

It was rejected because the authorization model here is not role-flat: it is *role + supplier binding + program scoping*, and a program manager's visible program list does not fit cleanly in custom claims (1000-byte limit, awkward for list-shaped data). That scoping lives in Postgres either way, so Firebase would solve authentication — the easy half with Spring Security — while leaving authorization untouched. The cost is a second identity store: creating a user becomes create-in-Firebase → set-claims → insert-row, a three-step flow with partial-failure states, instead of one transaction that writes the user, role, supplier binding and audit event atomically.

Revocation is a genuine tie rather than a win: Firebase can revoke via `revokeRefreshTokens`, but it additionally requires verifying `auth_time` server-side. Without that, a role change propagates only on token refresh.

**If this is revisited**, the clean version is Firebase Auth as a pure identity provider — verify the ID token, ignore custom claims, keep roles and scoping in Postgres. That keeps the free reset flows without splitting the authorization model in two.

### Path to Microsoft SSO (v2)

The client is a Microsoft shop and asked for SSO after v1, so v1 is built to make that an addition rather than a migration.

**Auth is split into two independent halves**, and only the first one is provider-specific:

```
credential verification  ─┐
  (password today,        ├─►  SessionService.issue(user)  ─►  session row + cookie
   Entra ID OIDC in v2)  ─┘
```

Adding Entra ID is then `spring-boot-starter-oauth2-client`, one config block pointing at the tenant's `issuer-uri`, and an `OidcUserService` that resolves the local user by verified email and calls the same `SessionService.issue`. Spring Security owns the authorization code flow. Nothing in the authorization model moves, because role, supplier binding and program scoping were never in the credential layer.

**SSO will not replace password auth — it will sit beside it.** Internal Acme users (~15) federate through Entra; supplier users are hundreds of people at external companies who will never exist in Acme's tenant. The system is permanently dual-auth, so login routes by user type. One session table, two ways to populate it.

Two things deliberately deferred to v2, when there is a real tenant to test against: SCIM provisioning (auto-create and deprovision from Entra group membership) and back-channel single logout. Session TTL is the interim answer to the second.

## 8. AI in the product

Two features, arrived at differently. **Field extraction** was a chosen stretch goal. **Criteria-based review is client-requested** (§12, answer 2) and therefore core. They share one pipeline — same document, same client, same advisory posture, same disclosure audit — which is most of why both fit.

### Field extraction from uploaded documents (stretch goal)

**Feature.** A reviewer can have the model read a certificate: insurer, policy number, coverage limits, effective and expiry dates, named insured, certificate holder, workers' compensation, signature. What it reads is compared against the submission, the supplier record, and the strictest program the certificate is held to, and the disagreements surface as findings — the reviewer sees *"the general liability aggregate shows USD 1,000,000; this program requires USD 2,000,000"* instead of opening the PDF and comparing by eye.

The same path reads a W-9 — name, business name, federal tax classification, address, signature — and compares it with the supplier's own profile. It is off unless Acme turns it on; see below.

**It checks the expiry date rather than supplying it.** The original design had extraction feed the compliance engine directly. What shipped is stronger: the supplier types the expiry at upload, it is required and validated, and the engine runs on that — extraction's job is to *disagree* when the document says otherwise. Applying the correction is a separate act by a reviewer with both dates in front of them, recorded with both values. A model that silently rewrote the date compliance runs on would have replaced a mistake nobody checks with a mistake nobody can see.

**The comparisons are Acme's rules, not the model's.** `CertificateFindings` is pure and sits in the domain layer; the adapter reads the document and the domain decides whether what it read is a problem. Two consequences worth stating: the findings stay stable when a prompt changes, and they are tested without a network. The name comparison is deliberately loose — "Northwind Staffing Partners" and "Northwind Staffing Partners, LLC" are one company, and a flag that fires on that pair trains a reviewer to click past every name mismatch, including the one that mattered.

**The W-9 is built, and it is off.** A W-9 carries a taxpayer identification number — an SSN for sole proprietors — so it is classified Restricted, and routing it through a third-party API is a data-governance decision rather than an engineering one. The client told us directly that a past vendor was careless with exactly this class of data.

That makes it Acme's decision, which is precisely why it is **a setting and not a refusal in code**: `acme.ai.w9-extraction-enabled`, false unless set, turned on by an environment variable in Cloud Run. An earlier draft of this system refused the W-9 outright and called that the safe answer. It was not. The memo tells Acme this call is theirs to make with their compliance counsel, and a decision they cannot act on without asking us for a deploy is not theirs — it is ours, wearing their name. The enabling conditions are unchanged and are stated where the setting lives: a data processing agreement with the model vendor, confirmed retention terms for submitted documents, and Acme's own sign-off.

**What is not configurable is what may be kept.** The taxpayer identification number is never extracted. There is no field for it in the JSON schema sent to the model, none in `W9Fields`, none in the parser, and the system prompt tells the model not to report it — three locks, and the absent field is the one that holds. The number lives in one place, the encrypted column on the supplier profile, and no value of the flag creates a second copy of it on an extraction row. Banking details have no switch at all, because nobody asked for one and a switch nobody asked for is a switch waiting to be found.

Every transmission of a Restricted document writes a disclosure event naming the setting that permitted it, so an auditor sampling the log sees when Acme's decision took effect without correlating it against a deploy.

### Criteria-based review (client-requested)

Asked which three or four reasons his team rejects documents for, so they could become one-click buttons, the client answered a different and better question: **give Acme a reference document and acceptance criteria to input, and check submissions against those.**

That moves the knowledge to the right place. A seeded catalog encodes what we guessed Acme rejects for, frozen on the day we guessed it. Criteria encode what Acme actually requires, per program and versioned, so the intended end state is that Marcus adds one the first time a supplier gets something wrong and every submission after that is checked against it.

**What shipped is that model minus its authoring screen, and the distinction matters.** Storage, versioning, evaluation, the checklist at review and one-click rejection are all built and tested. `PUT /api/requirements/{id}/criteria` replaces a requirement's list and returns the new version. No screen calls it — the ops console has no route for programs or requirements at all, and `/api/programs` is read-only. So criteria are authored through the API today, which means "maintained by the people who own the requirement, with no deploy" describes the design rather than the build. It is the first item in §12's v2 list, and it is frontend work against an endpoint that already exists.

**Authoring.** Each program requirement can carry two things:

- A **reference document** — a blank template for the supplier to fill in, or an annotated "what a good one looks like" for reviewers, each flagged for whether suppliers may see it. Showing a supplier the target before they upload is cheaper than rejecting them after, and it is the difference between a checklist item and a dead end.
- An **ordered list of acceptance criteria**, written in plain English:

```
Certificate of insurance — Program: Contoso Manufacturing        (criteria v3)
  1. Certificate holder reads "Acme Inc., 400 Market Street, Boston MA".
  2. General liability aggregate is at least USD 2,000,000.
  3. Workers' compensation coverage is present and unexpired.
  4. The policy expiry date is at least 30 days after today.
  5. The certificate is signed by an authorized representative.
```

**At review time** the model evaluates the submission against each criterion and returns `PASS` / `FAIL` / `UNCLEAR` with the span it relied on and a confidence. Marcus opens the document with a checklist beside it — four green, one red, one flagged unclear, each pointing at the place in the PDF it came from — rather than reading a certificate line by line against a program's requirements he has to remember.

**Rejection becomes specific.** One click on a failed criterion rejects the document using that criterion's own wording plus the evidence quote. The supplier is not told *"rejected — incorrect information."* They are told *"the general liability aggregate shows USD 1,000,000; this program requires USD 2,000,000."* That is the difference between one resubmission and three, and three rounds of email is where the 3–6 week cycle time actually goes. The seeded reason catalog stays for what criteria cannot express — illegible scan, wrong document entirely.

**Three constraints, all governance:**

- **Advisory, still.** A `FAIL` never auto-rejects; a `PASS` never auto-approves. A person clicks, and the audit trail records both what the model said and what the human decided. "AI acceptance criteria" describes the input to the review, not a delegation of the approval.
- **Criteria are versioned**, and every evaluation records the version it judged against. After the criteria change in June, "what was this document held to in March" still has an answer — the same reasoning that puts the template version on `SignatureRecord` (§4).
- **Classification gates it.** Evaluation transmits the document to an external processor, so it runs on Confidential and Internal documents only. A W-9 is Restricted, and criteria review has no equivalent of the extraction switch — nobody writes acceptance criteria for a W-9, so there is nothing to weigh against the exposure. Every transmission writes a disclosure event.

A requirement with no criteria is normal, not broken: review falls back to a human reading the document, exactly as it works today.

**Implementation.**

- Anthropic Java SDK, `com.anthropic:anthropic-java` (Kotlin uses the Java SDK).
- Model: `claude-opus-5`.
- PDFs go up as base64 `document` content blocks; scanned images as `image` blocks. No OCR dependency.
- **Structured outputs** rather than prompt-and-parse: the schema is derived from a Kotlin data class via `MessageCreateParams.builder().outputConfig(CoiExtraction::class.java)`, which returns a typed result and eliminates a whole class of parsing failures. Criteria evaluation uses the same mechanism with a `CriterionVerdict` class — criterion ID, verdict, evidence span, confidence — so a checklist of authored criteria comes back as a typed list rather than prose to be parsed.
- Adaptive thinking (`ThinkingConfigAdaptive`) with `OutputConfig.effort` — no `budgetTokens`, which is rejected on current models.
- Extraction is **advisory, never authoritative**. It prefills and flags; a human approves. Every extraction is stored with its confidence and surfaced in the activity log, so an auditor can see what the model suggested and what a person decided — and, critically, that no compliance state was ever set by the model alone.
- Each extraction writes an audit event recording that the document was transmitted to an external processor, when, and which model version responded. A third-party disclosure that leaves no trace is the finding an auditor writes up.
- The API key lives in Secret Manager. Both run asynchronously after upload, so a slow call never blocks the supplier's submit — the checklist fills in while the document sits in the review queue, which is where the latency is free.

## 9. Demo data and reset

The brief says the evaluators will drive the app themselves, with seeded realistic data and demo logins for both an ops user and a supplier. That makes the seeded world a **design artifact, not a fixture file** — it is the first thing anyone sees, and an empty or implausible dataset undoes the rest of the work.

**The seeded world is built so that every screen has something true to show:**

| Seeded state | Why it exists |
|---|---|
| ~12 suppliers spread across **every** onboarding stage | The pipeline view is the product; a pipeline with everything in one column demonstrates nothing. |
| 3 programs with genuinely different requirements | Makes requirement resolution (§4) visible rather than theoretical — including one supplier enrolled in two. |
| ≥2 certificates expiring inside 30 days | Populates Dana's upcoming-expirations view. An empty compliance screen is the worst possible first impression for the persona who signs off. |
| 1 supplier with an **expired** certificate | This is the audit finding that happened to them twice. It should be on screen, flagged, on first load. |
| 1 document rejected with a reason, awaiting resubmission | Shows both sides of the review loop, including what the supplier sees. |
| 1 supplier fully active and compliant | The end state — proof the flow terminates somewhere good. |
| 1 supplier just invited, nothing submitted | Exercises the real empty state of the supplier portal, which the brief calls out explicitly. |
| 1 supplier already active on one program, freshly pulled into a second | The reuse path the client confirmed (§4): most of the checklist arrives green, only the net-new items ask for anything. |
| **A simulated VMS holding 2 pending assignments and a writeback inbox** | Lets an evaluator run the loop themselves: release an assignment, watch onboarding start with no ops action, finish it, see the outcome land back (§5). |
| Authored acceptance criteria on the COI requirement, differing between two programs | Makes criteria-based review demonstrable, and shows the same certificate passing for one program and failing for another. |
| 1 failed integration message, dead-lettered | The unhappy path is the one nobody builds and everybody needs. Proves failures are visible and retryable rather than silent. |
| Backdated activity history | Cycle-time and the audit trail are meaningless with a single day of events. |

**Demo logins cover all four roles** — admin, ops, program manager and supplier — so a reviewer can see the access model rather than take it on faith. The brief asks for two; showing all four costs nothing and answers "does authorization actually work" before they ask.

**A reset is admin-only and re-seeds deterministically.** Evaluators will click everything, including approving and rejecting until the interesting states are gone. A demo that can be left unusable is an avoidable way to lose, and the reset doubles as proof the seeding is reproducible rather than hand-assembled.

## 10. Workstreams

| # | Workstream | Output |
|---|---|---|
| 0 | Foundation | Repo, Gradle/Vite scaffolding, Flyway schema, session auth, roles, account lifecycle (invite / reset / deactivate), hash-chained audit log with restricted DB grants, document classification, seed data, deploy pipeline |
| 1 | Supplier portal | Invite → register → company profile → document checklist → upload → signature |
| 2 | Ops console | Pipeline by stage with derived blockers, supplier record, review queue, activity timeline, auditor export |
| 2b | Administration module | Acme staff administration (invite / role / scope / deactivate / reset, with lockout safeguards), supplier user management, access-change history |
| 3 | Documents & e-signature | Versioned uploads with size/type validation, approve/reject with reason catalog, signature producing an executed PDF artifact |
| 4 | Notifications | Outbox table, templates, ops-visible log, optional real transport |
| 5 | **VMS integration** | `VmsConnector` port, simulated adapter, scheduled pull creating suppliers/enrollments idempotently, integration outbox with retry and dead-letter, `/ops/integrations` log, writeback on activation and compliance change |
| 6 | Compliance engine | Expiry dates, upcoming-expirations view, scheduled sweep, reminder emails |
| 7 | AI review | Criteria authoring and versioning, reference documents, per-criterion evaluation feeding one-click rejection; COI field extraction checking expiry dates and coverage; W-9 extraction behind a switch Acme owns, with no field for the taxpayer ID; disclosure auditing |
| 8 | Deliverables | Decision memo, Loom script, seeded demo world with reset (§9), demo logins for all four roles |

**Status.** This section describes the intended design; what is actually built on any given day lives in
[build-log.md](build-log.md), which is updated per workstream and is the document to trust when the two
disagree. Every workstream is delivered.

Two capabilities are built and switched off, each waiting on a credential rather than on code: outbox delivery
(4) needs an SMTP host, and the model behind criteria prefill and document extraction (7) needs an Anthropic
API key. The product is designed to be correct with both off — the outbox says plainly that nothing is leaving,
a reviewer ticks each criterion by hand, and expiry dates are typed and validated at upload whether or not
anything reads the certificate. A third switch is off for a different reason: W-9 extraction waits on Acme's
decision rather than on a credential, which is why §8 makes it a setting they can turn.

Workstreams 0–4 are the required core and ship first. **5 is now core too** — the client called the VMS integration critically important, which promotes it above the stretch goals rather than beside them. 6 is the remaining chosen stretch goal, and 7 is half core (criteria review, from answer 2) and half stretch (field extraction). 8 runs alongside rather than at the end.

**Two days did not get longer, so something gives.** Naming the cuts is more useful than discovering them at hour 40:

- ~~**W-9 extraction ships as documented design, not code.**~~ Reversed, and worth saying why rather than quietly restoring it. The cut was justified as governance — the document is Restricted, so refusing it in code looked like the safe answer. It was the wrong one twice over: the brief names the W-9 as the example of AI in the product, and the memo tells Acme the decision is theirs while leaving them unable to act on it without us. What shipped separates the two halves properly. Acme decides whether the document is sent, through a setting they own. We decide that the taxpayer identification number is never read or stored, and that is not configurable.
- ~~**The auditor export ships CSV only.**~~ Both shipped in the end: the filter came first and the PDF reused the PDFBox renderer already carrying the executed agreement, so the second format was a page layout rather than a capability.
- **Criteria authoring is a plain ordered list**, not a rule builder with operators and types. Plain English is what the client asked to input, and it is also what the model reads best.

What is deliberately *not* cut: the integration's failure handling. A demo that only shows the happy path is the one that raises the question of whether the unhappy path exists.

## 11. Deliberate non-goals for v1

SSO/SAML (the client said it comes later), bulk supplier import, program template authoring UI, a real e-signature vendor, AP/ERP integration, non-US suppliers, and mobile-native apps.

**VMS integration was on this list and has been removed from it.** The client's answer to question 3 promoted it to core scope (§5). What remains out is a *vendor-specific* connector: v1 ships the port, the automation and the reliability machinery against a simulated VMS, and the adapter for Fieldglass, Beeline, VNDLY or whichever platform Acme runs is the first thing built once that question is answered. Bulk supplier import stays a non-goal partly *because* of this — the VMS pull is the import path that matters.

On the governance side, deliberately deferred: SCIM provisioning, back-channel single logout, automated access-review campaigns with attestation, legal hold, and the infrastructure controls listed as configuration in §7 (project separation, CMEK, org policy constraints, audit-log sinks). Retention periods ship configurable but unset — the correct values are a legal answer Acme's counsel owns, and guessing them would be worse than asking.

The decision memo explains each cut and what would change to add it.

## 12. Open questions

The brief allows three questions during the build. All three were asked and answered. The answers are recorded below with what each one changed, because one of them changed the scope of the build materially.

**Answered by the client**

| # | Question | Answer | Consequence |
|---|---|---|---|
| 1 | When a supplier already onboarded for one program joins a second, do they re-submit everything, or are the company profile, W-9 and COI reused with only program-specific items collected? Is an expired COI a fact about the supplier or about their standing in one program? Do requirements ever vary by supplier attribute — jurisdiction, entity type, contract value? | Confirmed. A supplier dashboard shows what is already on file, pre-filled; a new program may add net-new information on top of it. | The model was already built this way (§4). What the answer adds is a **UI commitment**: the second-program experience must visibly show what is reused, not silently omit it. See § Requirement resolution. |
| 2 | Is a supplier compliant only when every required document is approved and unexpired, or is there a tier that can begin placing contractors with a subset in hand? When insurance expires mid-engagement, does the system flag, notify, or block? And what are the three or four most common rejection reasons today, so they can be one-click instead of a blank text box? | Rather than a fixed reason list: **each requirement carries a reference document plus acceptance criteria that Acme inputs**, and the AI evaluates submissions against those criteria. | Replaces a seeded catalog with an authored, versioned rule set per requirement, and makes rejection reasons *specific* — the failed criterion, in Acme's own words. See §8 § Criteria-based review. |
| 3 | Does Acme need to store supplier bank account details, or is the goal to collect, verify and hand off to Finance/AP? On activation, does anything need to leave this tool — a VMS, an AP system — or is this the end of the line for v1? | **"Critically important"**: pull from the VMS to kick off automated onboarding, and push the result back to the VMS as the system of record. | The largest change in the build. VMS integration moves from deliberate non-goal to core scope, and the tool stops being the origin *or* the terminus of the process. See §5. |

**Two sub-questions came back unaddressed**, and are recorded as still-open rather than treated as settled:

- **Is there a partial-compliance tier, and does expired insurance block or only flag?** The working assumption stands: binary compliant / non-compliant, `EXPIRING_SOON` at ≤30 days, expiry flags and notifies rather than hard-blocking. This is now more consequential than it was, because the compliance state is pushed to the VMS (§5) — if the VMS is what actually gates a placement, then "flag" here means "block" there, and Acme should decide which system holds that authority.
- **Are bank details stored here or handed to Finance/AP?** The working assumption stands: stored, encrypted, masked after submission. The writeback makes the follow-on question concrete — see the next list.

**Queued for the next conversation** — the material for the memo's "what I would ask next", now led by the integration:

1. **Which VMS, and what access will we have?** Fieldglass, Beeline, VNDLY and Bullhorn differ in API shape, auth and whether they can push at all. v1 ships against a simulated VMS behind a connector port (§5) precisely so this answer changes an adapter rather than the architecture — but it is the first thing needed to make the integration real, along with sandbox credentials and a field mapping.
2. **Which system wins on conflict?** If the VMS holds a supplier's legal name and this tool holds a W-9 showing a different one, one of them is wrong. v1 flags the divergence for a human rather than overwriting either side; a standing rule needs Acme's call.
3. **Does the writeback include restricted fields?** Tax ID and bank account are classified Restricted (§7), so v1 does not transmit them. If AP expects this tool to be the source of banking data, that is a governance decision with a data-processing answer attached, not a mapping change.
4. **Who authors acceptance criteria, and can a failed criterion ever auto-reject?** v1 keeps every rejection a human action with the AI advisory. If Marcus wants "expired on arrival" to reject automatically, that is a small change and a large policy shift.
5. **Retention periods per document type.** Tax and payment records carry statutory obligations that outlast the supplier relationship. The schema is configurable and deliberately unset; the correct values are Acme's counsel's answer, not an engineering guess.
6. **External processing of W-9s.** The switch exists and is off (§8). Turning it on needs a data processing agreement, confirmed vendor retention terms, and a sign-off — and the open question is whose. Dana, or a compliance function above her? This is the one queued question with a code path already waiting on the answer.
7. **Who holds `ADMIN`?** Is Marcus an admin, or is Acme-staff administration a separate function? This determines whether the administration module is a daily surface or a rarely-touched one, which changes where it sits in the navigation.
8. **Program manager visibility.** Do they see every supplier in their program including those still onboarding, or only active ones? v1 shows all with stage visible, on the assumption that a program manager wants to know what is coming.
9. **Must a shared document clear the strictest program, or can it be good enough for one and not another?** Programs set different coverage minimums, and a supplier-scope certificate is one document held by all of them. If the answer is "strictest wins", v1 is already right and the bar just needs naming on screen. If it is "per program", the certificate needs a decision per enrollment — approved for Meridian at USD 1M, rejected for Northstar at USD 2M — which is a schema change and a different review flow. Until Acme answers, the numeric bar is enforced by a reviewer through that program's acceptance criteria rather than by the compliance engine (§4).
10. **Do Acme's enterprise clients impose their own vendor-governance requirements** that this system needs to satisfy — evidence formats, retention floors, access-review cadence? Acme is audited by its clients, so their requirements may be stricter than Acme's own.
