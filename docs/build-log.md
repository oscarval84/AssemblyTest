# Build log

> Language rule: this repository is English-only. See [CLAUDE.md](../CLAUDE.md), Rule #0.

Where the build stands, what is deliberately absent, and what to pick up next. The workstream numbers refer to
the table in `architecture.md` §10.

## Delivered

| # | Workstream | State | Where |
| --- | --- | --- | --- |
| 0 | Foundation | **Done** | Session auth, account lifecycle, hash-chained audit log, transactional outbox, document storage port, demo seeding with an admin-only reset |
| 1 | Supplier portal | **Done** | Invite → accept → company profile → per-program checklist → upload → signature producing an executed PDF |
| 2 | Ops pipeline | **Partly** | Pipeline grouped by who is blocking, supplier record with compliance per program and the activity timeline. The auditor export is not built |
| 2b | Administration | **Done** | See below |
| 3 | Documents & review | **Done** | Review queue ordered by wait time, approve/reject with a reason and note, segregation of duties enforced |
| 4 | Notifications | **Partly** | Outbox written transactionally, inspectable at `/ops/outbox`, drained by a scheduled job. No real transport yet — the `MailTransport` port has one implementation and it delivers nothing, on purpose |
| 5 | VMS integration | **Done** | `VmsConnector` port with a simulated adapter, idempotent pull that starts onboarding with no ops action, transactional integration outbox with backoff and dead-lettering, conflict flagging, and `/ops/integrations` |
| 6 | Compliance engine | **Done** | Nightly sweep reminds at 30 days, 7 days and the morning after; reopens onboarding on expiry; records every transition. Ops sees the same list at `/ops/expirations` |
| 7 | AI review | Not started | |
| 8 | Deliverables | Not started | Decision memo and demo script |

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

## Known gaps, all deliberate

- **No malware scanning on upload.** Documented in `architecture.md` §7 as an accepted v1 gap, not an oversight.
- **Nothing is actually delivered.** The drain runs and reports what it would send; `OutboxOnlyTransport`
  refuses to deliver rather than marking messages `SENT` it never sent, because a log that lies about delivery
  is worse than one that admits it is switched off. A real transport is a second implementation of
  `MailTransport` and a credential.
- **The scheduler is not wired.** `/internal/jobs/compliance-sweep` and `/internal/jobs/outbox-drain` exist and
  are authenticated with a shared secret; creating the three Cloud Scheduler jobs is deploy configuration we
  cannot do without Acme's GCP project.
- **`rejection_reason` still models the seeded catalog.** The client's second answer replaced it as the primary
  path with authored, versioned acceptance criteria (Workstream 7); the catalog remains the always-available
  baseline. Resolve this before building any UI on top of rejection reasons.
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
