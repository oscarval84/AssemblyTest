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
| 4 | Notifications | **Partly** | Outbox written transactionally and inspectable at `/ops/outbox`. No real transport and no scheduled drain |
| 5 | VMS integration | Not started | |
| 6 | Compliance engine | **Partly** | Expiry dates, computed compliance and the warning band all work. No scheduled sweep and no reminder emails |
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

## Known gaps, all deliberate

- **No malware scanning on upload.** Documented in `architecture.md` §7 as an accepted v1 gap, not an oversight.
- **The outbox is never drained.** Messages are written and shown; delivery needs a transport and a scheduled
  job (Workstream 4).
- **Compliance is computed but never swept.** Expiry drives status correctly on every read; nobody is notified
  before it happens (Workstream 6).
- **`rejection_reason` still models the seeded catalog.** The client's second answer replaced it as the primary
  path with authored, versioned acceptance criteria (Workstream 7); the catalog remains the always-available
  baseline. Resolve this before building any UI on top of rejection reasons.
- **Deploy pipeline is not built.** Jib, Firebase Hosting configuration and the GitHub Actions workflow need
  Acme's GCP project and Workload Identity Federation identifiers, which we do not have.

## Running it

See [local-development.md](local-development.md). The short version: `docker compose up -d`, then
`cd backend && ./gradlew bootRun --args='--server.port=8085'`, then `cd frontend && npm run dev`. The demo world
seeds itself on an empty database and the sign-in screen lists the accounts.
