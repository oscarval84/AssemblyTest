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
| 7 | AI review | **Partly** | Criteria authored, versioned and checked at review time, with one-click rejection in Acme's own words, and model prefill behind an API key with the classification gate in code. COI field extraction is not built |
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
runs on Confidential and Internal documents only. A W-9 is Restricted and is refused in `CriteriaPrefillService`
— in code, not behind a setting, because a setting is something somebody eventually turns off. The checklist
reports `modelAvailable = false` for a Restricted document even where a key is configured, so the button never
appears in the first place. `CriteriaPrefillTest` asserts both halves: the refusal, and that nothing reached the
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

## Known gaps, all deliberate

- **No malware scanning on upload.** Documented in `architecture.md` §7 as an accepted v1 gap, not an oversight.
- **Nothing is delivered here, but the transport exists.** `SmtpMailTransport` registers itself only when a
  mail host is configured; with none, `OutboxOnlyTransport` refuses to deliver rather than marking messages
  `SENT` it never sent, and `/ops/outbox` reports delivery as switched off. Turning it on is four environment
  variables and a credential — see `local-development.md` § Sending email for real. A configured transport with
  no host is not fatal either: the screen warns that messages are queueing behind a misconfiguration.
- **The scheduler is not wired.** `/internal/jobs/compliance-sweep` and `/internal/jobs/outbox-drain` exist and
  are authenticated with a shared secret; creating the three Cloud Scheduler jobs is deploy configuration we
  cannot do without Acme's GCP project.
- **COI field extraction is not built.** The stretch goal, and the one whose absence costs least: expiry dates
  are typed at upload and validated, so the compliance engine is already correct without it. It is a second
  implementation behind the same port the criteria prefill uses, with the same classification gate.
- **Deploy pipeline is not built.** Jib, Firebase Hosting configuration and the GitHub Actions workflow need
  Acme's GCP project and Workload Identity Federation identifiers, which we do not have.

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
