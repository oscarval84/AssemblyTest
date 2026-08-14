# Acme supplier onboarding

A v1 supplier-onboarding platform for **Acme Inc.**, a managed service provider that onboards ~300
staffing suppliers a year across 40+ client programs. Today that process is email, spreadsheets and
a legacy e-sign tool; it takes three to six weeks per supplier, and twice it has let a supplier work
on an expired insurance certificate.

Built in two focused days. The bet was a small number of genuinely finished flows over a long
feature list.

## What runs

A supplier is invited, registers, completes a company profile, sees a per-program checklist of what
Acme needs and why, uploads documents, and signs the master agreement — which produces an executed
PDF, not a record that someone clicked "I agree". Ops reviews from a queue ordered by wait time and
hands a document back with a reason the supplier reads verbatim; approving the last outstanding
document completes onboarding and activates the supplier's programs. A nightly sweep chases
expiring certificates before they lapse. Assignments pull in from the VMS and outcomes are written
back. Every state change is an event in an append-only, hash-chained log, and that log exports as a
spreadsheet or as a document you can hand to an auditor.

Two capabilities are built and switched off, each waiting on a credential rather than on code:
**email delivery** (an SMTP host) and **the model that prefills the review checklist** (an API key).
Both are covered in [docs/local-development.md](docs/local-development.md); the product is designed
to be correct with either of them off.

## Running it

```bash
docker compose up -d                                  # PostgreSQL 17 on 127.0.0.1:5432
cd backend && ./gradlew bootRun --args='--server.port=8085'
cd frontend && npm install && npm run dev             # http://localhost:5173
```

Port 8085, not 8080: macOS ships an Apache on 8080. The demo world seeds itself on an empty
database and the sign-in screen lists every account (password `Onboarding2026!` for all of them).

```bash
cd backend && ./gradlew test                          # unit + schema tests, via Testcontainers
cd frontend && npm run typecheck && npm run build
```

## Where things are

| | |
|---|---|
| [docs/decision-memo.md](docs/decision-memo.md) | What we chose, what we cut, and the five questions we need Acme to answer |
| [docs/demo-script.md](docs/demo-script.md) | A twelve-minute walkthrough, four sign-ins |
| [docs/architecture.md](docs/architecture.md) | The design in full — domain model, integration, security and governance |
| [docs/build-log.md](docs/build-log.md) | Where the build actually stands, workstream by workstream |
| [docs/local-development.md](docs/local-development.md) | Setup, the API client, turning email and the model on |
| [CLAUDE.md](CLAUDE.md) | The brief and the conventions this repository is written to |

`backend/` is Kotlin on JDK 21 with Spring Boot 4 and Flyway, layered `domain / application /
adapter`. `frontend/` is React 19 + Vite + TypeScript with MUI under a custom theme, and its typed
API client is generated from the backend's own OpenAPI document.

## The three ideas worth knowing before reading the code

**Compliance status is computed, never stored.** A stored `COMPLIANT` flag becomes wrong at midnight
with nothing having written to the row. It is derived from the current date in Acme's business time
zone on every read; the scheduled sweep exists to notify and to record transitions, not to keep a
column truthful.

**The audit log is evidence, not plumbing.** Each event carries the hash of its predecessor and the
application's database role holds only `INSERT` and `SELECT` on the table, so tampering is
detectable rather than merely forbidden. Document *reads* are audited too. `/ops/audit` walks a
supplier's chain on demand and exports the result.

**Classification decides handling.** Every document type carries one. Restricted documents — a W-9,
banking details — are encrypted at rest, masked after submission, never pushed to the VMS, and never
transmitted to a third-party model. That last refusal lives in code rather than in a configuration
flag, because a flag is something somebody eventually turns off.
