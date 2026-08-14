# Build log

> Language rule: this repository is English-only. See [CLAUDE.md](../CLAUDE.md), Rule #0.

Where the build stands, what is deliberately absent, and what to pick up next. The workstream numbers refer to
the table in `architecture.md` §10.

## Delivered

| # | Workstream | State | Where |
| --- | --- | --- | --- |
| 0 | Foundation | **Done** | Session auth, account lifecycle, hash-chained audit log, transactional outbox, document storage port, demo seeding with an admin-only reset |
| 1 | Supplier portal | **Done** | Invite → accept → company profile → per-program checklist → upload → signature producing an executed PDF |
| 2 | Ops pipeline | **Done** | Pipeline grouped by who is blocking, supplier record with compliance per program, the activity timeline, and the auditor export at `/ops/audit` |
| 2b | Administration | **Done** | See below |
| 3 | Documents & review | **Done** | Review queue ordered by wait time, approve/reject with a reason and note, segregation of duties enforced |
| 4 | Notifications | **Done** | Outbox written transactionally, inspectable at `/ops/outbox`, drained by a scheduled job. `SmtpMailTransport` delivers wherever a mail host is configured; with none, delivery is off and the screen says so |
| 5 | VMS integration | **Done** | `VmsConnector` port with a simulated adapter, idempotent pull that starts onboarding with no ops action, transactional integration outbox with backoff and dead-lettering, conflict flagging, and `/ops/integrations` |
| 6 | Compliance engine | **Done** | Nightly sweep reminds at 30 days, 7 days and the morning after; reopens onboarding on expiry; records every transition. Ops sees the same list at `/ops/expirations` |
| 7 | AI review | **Done** | Criteria authored, versioned and checked at review time with one-click rejection in Acme's own words; model prefill and field extraction behind an API key, the W-9 additionally behind a switch Acme owns |
| 8 | Deliverables | **Done** | [decision-memo.md](decision-memo.md), [demo-script.md](demo-script.md), root README, seeded demo world with an admin-only reset |

## Workstream 2b — administration

Two surfaces that must not share a screen, because keeping them apart is what stops an ops user from granting
Acme-internal access while working a supplier's file.

| Surface | Manages | Who operates it | Where |
| --- | --- | --- | --- |
| Acme staff administration | Acme's own people | `ADMIN` only | `/ops/admin/users` |
| Supplier user management | External users at one supplier | `OPS` and `ADMIN`, scoped to that supplier | Inside the supplier record |

**Backend, already in place before this workstream:** `StaffAdministrationService` (list, change role, set program
scope, deactivate, reactivate, access history), `AdminController` exposing all of it, `PasswordResetService`
issuing a reset on a user's behalf, and the two lockout safeguards in `AdminSafeguards` with unit tests.

**Progress**

- [x] Supplier user deactivation and reactivation, ops-scoped to one supplier
- [x] Acme staff administration screen: the access report with actions attached
- [x] Invite, change role, adjust program scope, deactivate, reactivate, send a reset
- [x] Access-change history per user
- [x] Supplier user management inside the supplier record

**A bug this workstream surfaced, worth recording.** Every mutating request was
intermittently answered with a 403 that read like an authorization failure and was
not one. The CSRF cookie was being deleted on *every* authenticated response:
`CookieCsrfTokenRepository` clears its cookie whenever an authentication is
established, and this application establishes one per request by design, because
the session filter resolves the caller from the database rather than from a
servlet session. A write that raced a page's parallel reads therefore sent a token
the browser no longer held. `StableCookieCsrfTokenRepository` refuses the clear;
the client also now returns a new `Request` from its middleware rather than
mutating headers in place. It went unnoticed until now because the earlier flows
were driven through the API in tests, where each call carries a freshly read
token.

## Workstream 5 — VMS integration

Inbound, a scheduled pull turns assignments into onboarding that has already started: Marcus finds the supplier
in his pipeline, at the right stage, with the right checklist. Outbound, activation and compliance changes are
queued in the same transaction as the state change and pushed with exponential backoff, dead-lettering after
six attempts rather than looping quietly.

**Idempotency is the whole game.** Every operation keys on the external identifier through `vms_link`, so
replaying yesterday's sync changes nothing — and re-invitation in particular never happens twice.

**Neither system wins a conflict.** When the VMS's legal name contradicts the approved W-9, both values are
kept and a flag is raised. One of them is wrong and a human decides which.

**This is a working integration against a simulated VMS, not a proven integration with a vendor.** What it
demonstrates is the contract, the automation and the reliability machinery; a real connector is a second
implementation of the same port plus credentials and a field map.

## Workstream 7 — criteria-based review

Asked which three or four reasons his team rejects documents for, the client answered a better question: let
Acme input the acceptance criteria, and check submissions against those. A seeded catalog encodes what we
guessed on the day we guessed it; authored criteria encode what Acme actually requires, per program, with no
deploy.

Criteria are **versioned as a set**: editing retires the old rows and writes new ones, and every verdict stores
the text it judged against. After the criteria change in June, "what was this document held to in March" still
has an answer.

A failed criterion becomes the rejection, in Acme's own words plus what the document showed — *"the general
liability aggregate shows USD 1,000,000; this program requires USD 2,000,000"* rather than *"rejected —
incorrect information"*. That is the difference between one resubmission and three, and three rounds of email
is where the 3–6 week cycle time actually goes.

**The model prefill is built, behind an API key.** `AnthropicCriteriaEvaluator` sends the document and Acme's
criteria to Claude with structured outputs, and the checklist comes back as typed verdicts — each with the span
it relied on and a confidence — stored with `source = MODEL` beside whatever the reviewer later decides. With no
key, `DisabledCriteriaEvaluator` is the active implementation, the button is not offered, and a person ticks
each criterion; that is the fallback the design requires anyway, because a `FAIL` never auto-rejects and a
`PASS` never auto-approves.

**The classification gate is the part worth reviewing.** Evaluation transmits a document to a third party, so it
runs on Confidential and Internal documents only. A W-9 is Restricted and is refused in `CriteriaPrefillService`,
in code and with no setting attached — nobody writes acceptance criteria for a W-9, so there is nothing here to
weigh against the exposure. (Field *extraction* is the case where there was something to weigh, and it got a
switch; see below.) The checklist reports `modelAvailable = false` for a Restricted document even where a key is
configured, so the button never appears in the first place. `CriteriaPrefillTest` asserts both halves: the refusal, and that nothing reached the
model before it.

**Disclosure is recorded before the call, not after.** `DOCUMENT_DISCLOSED` commits in its own transaction
naming the processor, the model and the document's classification; the verdicts commit in a second one. A call
that times out therefore still leaves the record that the document left the building, which is the ordering an
auditor cares about and the opposite of what one transaction around the whole thing would give.

## Workstream 2 — the auditor export

Dana asked for this by name: *"a history I can hand to an auditor."* The timeline on a supplier's record
answered half of it. This is the other half — the same events, filtered by supplier, program and date range,
as a CSV *or a PDF* at `/ops/audit`, plus a link from each supplier's record that arrives pre-filtered to that
company. Two formats because the recipients differ: the CSV is filtered and pivoted by whoever analyses it, the
PDF is what gets attached to an audit response. Same query, same events; the PDF caps far lower on purpose,
because past a couple of thousand events it is hundreds of pages nobody reads and the CSV is the honest answer.

**It is an audit artifact, so it is built like one.**

- **Every row carries its chain key, its position and its hash**, so the file can be checked against the system
  it came from rather than believed. Next to the filters, choosing a supplier walks that chain and reports
  whether it is unbroken and how many events it holds. A history is only evidence if the person handing it
  over can say it is whole, and that claim should not need an engineer.
- **Taking a copy is itself an event.** `AUDIT_EXPORTED` records the filter and the row count, in the exported
  supplier's own chain when the export names one and in the system chain otherwise. Data leaving the system is
  recorded wherever it goes, and an export of the audit log is the case that most needs it.
- **Refusing beats truncating.** Past 50,000 events the export declines and says how to narrow it. An audit
  artifact that silently stops halfway is the one failure mode this feature cannot have.
- **A company name is not trusted content.** A legal name beginning `=` is a formula to a spreadsheet, and
  these files are opened in Excel by definition; `Csv` neutralises it, writes CRLF, and prefixes a BOM so
  accented names survive the trip.
- **Dates are calendar dates in Acme's time zone**, converted to instants at the edge, both ends inclusive.
  "To the 14th" means through the end of the 14th, which is the only reading the person filling in the form
  has in mind.

A program manager gets the same screen, scoped to their programs, and the copy says so rather than promising
"every supplier" and quietly delivering fewer. Asking for a supplier outside their programs is refused rather
than answered with an empty file — "no events" and "not yours to read" must not look alike.

**PDF is not built**, and that was already the plan: `architecture.md` §11 named CSV-only as one of the three
cuts taken on day one.

## A test that failed for six hours a day

`ComplianceSweepTest` resolved "today" with the machine's `LocalDate.now()` while the application resolves it
in Acme's business time zone. Run from a laptop west of New York after 18:00, the two disagree by a day and
the reminder arithmetic came out one short — the same off-by-one the whole compliance engine exists to
prevent, sitting inside the test written to catch it. It now asks `ComplianceEvaluator.today()`, which is the
function the sweep itself uses.

`ChainVerifier` also had no test for the case that matters: a verifier that always answered "intact" would
have passed the entire suite. There are now four, including the careful attack — rewriting an event *and*
recomputing its hash, which moves the break to its successor rather than hiding it.

## Resolving `rejection_reason` against authored criteria

The seeded catalog was built before the client answered the question it was guessing at, and the product then
had two vocabularies for the same act. The UI papered over it: rejecting from a failed criterion sent a fixed
catalog code, so a supplier whose certificate was unsigned was told *"coverage limits below the program
minimum"*.

A rejection is now grounded in exactly one thing (V8): an authored criterion — the primary path, quoted back to
the supplier verbatim — or a catalog reason, for what criteria cannot express, which is real and narrow: an
illegible scan, the wrong document entirely. The schema enforces the exclusivity and still refuses a rejection
with neither. One `rejection_label` column resolves to the criterion's own text or the catalog label, so every
screen and every email reads the same source and neither path can drift into describing a rejection differently.

## Workstream 7 — field extraction

The stretch goal, built last because the product is correct without it — and that is exactly what shaped it.
The supplier types the expiry date at upload, it is required and validated, and the whole compliance engine
runs on it. What was missing is that **nobody checked it against the document**, and an expiry date wrong by
two months is the shape of the failure that let a supplier work on a lapsed certificate twice.

So extraction reads the certificate and **disagrees out loud**. It never supplies the date it checks:
`applyExtractedExpiry` is a separate action a reviewer takes with both dates in front of them, and it records
both. Replacing a mistake nobody checks with a mistake nobody can see would not have been an improvement.

**The comparisons are Acme's rules, not the model's.** `CertificateFindings` is pure and lives in the domain
layer: the model reads the document, and this decides whether what it read is a problem — expiry against the
submission, aggregate against the strictest program the certificate is held to, named insured against the
supplier record, Acme named as certificate holder, workers' compensation, signature. That split keeps the
findings stable when a prompt changes and testable without a network.

Two details are load-bearing and both point the same way — a reviewer must be able to trust a value and check
a gap:

- **Every field is nullable, and the prompt insists on null over a guess.** A cropped scan comes back with
  holes rather than plausible numbers.
- **The name comparison is deliberately loose.** "Northwind Staffing Partners" and "Northwind Staffing
  Partners, LLC" are one company, and an insurer writes whichever is on the policy. Flagging that pair would
  train a reviewer to click past every name mismatch — including the one that mattered.

Same ordering as criteria review: the disclosure event commits before the document is transmitted, and
`DOCUMENT_EXPIRY_CORRECTED` carries both dates because compliance runs on that column.

**The W-9, and a position that was wrong.** This shipped reading certificates only, and refused a W-9 in code
on the grounds that a Restricted document should never reach a processor. The brief names the W-9 as its
example of AI in the product, and `CLAUDE.md` had always described that path as *disabled by default* — which
presupposes a switch. More to the point, the decision memo tells Acme this call is theirs to make with their
compliance function, and a call they cannot act on without asking us for a release is not theirs.

So the W-9 is built, off by default, behind `acme.ai.w9-extraction-enabled`. What that separation buys is
worth stating: **Acme decides whether the document is sent; we decide what may be kept.** The taxpayer
identification number is not extracted at all — no field in the JSON schema, none in `W9Fields`, none in the
parser, and the system prompt says so too. Three locks, and the absent field is the one that holds, because no
value of the flag creates somewhere to put the number. `W9ExtractionTest` runs the same context with the switch
on and asserts the stored row contains neither the supplier's tax ID nor any field that could carry one.

`TaxFormFindings` is the domain half. It flags a form filed under a different company — the failure that ends
with a 1099 going to the owner's other entity — and an entity type contradicting the profile. That comparison
maps each side to the *set* of kinds its wording admits rather than comparing strings, because the W-9's first
checkbox reads "Individual/sole proprietor or single-member LLC" and a profile that says "LLC" is not
disagreeing with it.

Every transmission of a Restricted document writes a `DOCUMENT_DISCLOSED` event naming the setting that
permitted it, so the log shows when Acme's decision took effect rather than requiring it be correlated with a
deploy.

## Known gaps, all deliberate

- **The demo reset does not work on the deployed instance, and that is the control working.** It clears
  the world with `TRUNCATE ... CASCADE`, which reaches `activity_event` through its actor foreign key,
  and `V2` limits the application's role there to `SELECT` and `INSERT`. It used to surface as a 500;
  it now checks `has_table_privilege` up front and answers in a sentence. Granting the privilege would
  have made the button work and made the memo's promise to Dana false, which is the wrong trade.
- **A trailing newline on the API key put the key into the logs.** Pasting a secret and pressing Enter
  before `Ctrl-D` stores it with a `\n`; the SDK then throws `Unexpected char 0x0a in X-Api-Key value`,
  and that exception **quotes the key in its message**, so logging the throwable wrote a live credential
  to Cloud Logging in plain text. The key is now trimmed where the adapters are constructed, so the
  exception cannot occur — a better answer than scrubbing logs, because a log entry cannot be recalled
  and the only remedy after the fact is rotation. Found by running a real extraction against the deployed
  service, which is the only place it could have appeared.
- **An unknown API address answered 500, not 404.** Spring raises `NoResourceFoundException` for a URL no
  controller serves, nothing handled it, and it fell through to the catch-all that apologises for a fault on
  Acme's side. Two costs: a supplier following a stale link was told Acme was broken, and real 500s sat in the
  logs among typos. Found by sweeping the deployed app rather than by a test — the suite calls services
  directly and has no HTTP layer, so no test could have caught it. Fixed in `ApiExceptionHandler` and verified
  against the deployed URL.
- **The session cookie has to be called `__session` behind Firebase Hosting**, which is now
  configuration (`SESSION_COOKIE_NAME`) rather than a constant. Hosting strips every other incoming
  cookie before proxying to Cloud Run, so the first deploy signed in successfully and then 401'd on
  every subsequent request. Named here because the symptom points nowhere near the cause, and because
  the diagnostic is worth reusing: the same request against the Cloud Run URL directly worked, which
  isolates it to the proxy in one step.
- **`V2`'s comment block still describes the grants more strongly than they hold.** The accurate version is
  in `architecture.md` §7 and `deployment.md`, and not in the migration, because the file has been applied
  and Flyway checksums it — editing it stops every existing database from starting. That is a rule rather
  than an inconvenience, and it is written down in [local-development.md](local-development.md).
- **One database role migrates and serves.** `activity_event`'s grants are real — verified against a
  non-superuser owner, which is refused `UPDATE` after `V2`'s revoke — but the deployed role owns the table,
  and an owner can re-grant to itself and disable its own triggers. So the grant layer stops a bug and stops
  a SQL injection; it does not stop someone with full control of the application's database session who
  thinks to run `GRANT` first. The hash chain is the layer that survives that case, which is why the
  auditor-facing claim is *detectable*, not *prevented*. Splitting into a migration owner and a runtime role
  that owns nothing is deploy configuration — see [deployment.md](deployment.md) § Cloud SQL — and is not
  done.
- **No malware scanning on upload.** Documented in `architecture.md` §7 as an accepted v1 gap, not an oversight.
- **A program's numeric constraints are stated and reviewed against, not enforced by the engine.** Programs
  differ in which documents they require and in the bar each one sets — Northstar wants USD 2M general
  liability, Meridian 1M — and that difference is carried end to end: the checklist is built per enrollment and
  states each program's own minimum, the acceptance criteria are seeded per program requirement with that
  minimum written into the text, and extraction compares the certificate against the strictest program holding
  it. What does *not* happen is `ComplianceEvaluator` reading `constraints`; it judges presence, review status
  and expiry only. So the numeric bar is a human gate reached through the criteria checklist, not an automatic
  one. This was found by re-reading the code against the brief rather than by a failure, and `architecture.md`
  §4 previously overstated it — it claimed one certificate could be compliant for program A and non-compliant
  for B simultaneously, which a supplier-scope document cannot express, because it is one row with one status.
  Closing it properly means a per-enrollment decision on a shared document, which is a schema change with a
  product question attached and is queued in §12 rather than half-built.
- **Nothing is delivered here, but the transport exists.** `SmtpMailTransport` registers itself only when a
  mail host is configured; with none, `OutboxOnlyTransport` refuses to deliver rather than marking messages
  `SENT` it never sent, and `/ops/outbox` reports delivery as switched off. Turning it on is four environment
  variables and a credential — see `local-development.md` § Sending email for real. A configured transport with
  no host is not fatal either: the screen warns that messages are queueing behind a misconfiguration.
- **The scheduler is not wired.** `/internal/jobs/compliance-sweep` and `/internal/jobs/outbox-drain` exist and
  are authenticated with a shared secret; creating the three Cloud Scheduler jobs is deploy configuration we
  cannot do without Acme's GCP project.
- **W-9 extraction is off in this build**, and needs `AI_W9_EXTRACTION_ENABLED=true` plus the API key. That
  is Acme's decision to make, not a credential we are missing — see the memo, "What we need from you", item 3.
- **Nothing reads a certificate on upload.** Extraction is a deliberate act by a reviewer rather than something
  that happens when a supplier submits. Running it automatically is a scheduler and a queue away, and it would
  transmit every certificate to a processor whether or not anyone was going to look at the result — which is a
  cost and a disclosure decision Acme should make rather than inherit.
- **There is no Cloud Storage adapter.** `LocalDocumentStore` is the only implementation of the port, and
  the architecture's §7 describes a GCS adapter serving V4 signed URLs. Deployment closes the durability half
  by mounting the bucket into Cloud Run as a volume and pointing the filesystem adapter at it — no code change,
  durable, shared across instances. What is genuinely absent is the signed URL, and that is the better trade at
  this scale: streaming through `/api/documents/{id}/download` means every read is authorized and audited when
  it happens, where a signed URL is a bearer credential nobody can revoke before it expires. See
  [deployment.md](deployment.md) § The documents bucket.
- **No CI/CD pipeline.** Jib and the Firebase Hosting configuration are now in the repo and
  [deployment.md](deployment.md) is a working runbook, but the deploy runs from a developer machine. The
  GitHub Actions workflow with Workload Identity Federation that `architecture.md` §3 names is not built — and
  it was self-imposed scope: the brief asks for a deployed URL and says free tiers are fine, and never
  mentions CI/CD.

## Workstream 6 — the compliance engine

The sweep does **not** keep a status column truthful — compliance is computed from the current date on every
read, so it is already correct at midnight with nothing running. The job exists for the two things a computed
value cannot do: tell somebody, and record that the transition happened.

Reminders fire at 30 days, 7 days, the day of expiry, and the day after. Each is claimed once per document
version through a primary key on `expiry_reminder`, so idempotency is a database guarantee rather than a
convention — the job runs daily and a certificate sits in the warning band for a month, which without this
would be thirty emails and a supplier who filters Acme out.

An expired document reopens document collection and writes a `DOCUMENT_EXPIRED` event to the supplier's chain.
It flags rather than blocks: whether an expired certificate stops a placement is the VMS's decision, and that
question is still open with the client.

## Running the scheduled jobs by hand

```bash
curl -X POST -H "X-Job-Token: local-development-job-token" \
  http://localhost:8085/internal/jobs/compliance-sweep
```

## Running it

See [local-development.md](local-development.md). The short version: `docker compose up -d`, then
`cd backend && ./gradlew bootRun --args='--server.port=8085'`, then `cd frontend && npm run dev`. The demo world
seeds itself on an empty database and the sign-in screen lists the accounts.
