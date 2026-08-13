# Acme Supplier Onboarding — Project Guide

## ⚠️ Rule #0 — Everything shipped is written in English

The working conversation may happen in Spanish. **The repository is English-only.** No exceptions.

This covers, without exception:

- Identifiers: variables, functions, types, files, folders, DB tables and columns
- Comments, JSDoc, TODOs
- Documentation: README, ADRs, the decision memo, this file
- Commit messages, branch names, PR titles and descriptions
- Test names and fixtures
- Seed and demo data
- **User-facing UI copy**, emails, error messages, empty states

If a Spanish string ever lands in the repo, translate it — do not match the surrounding style. The client, the evaluators, and every reader of this codebase are English speakers.

---

## What this is

A v1 supplier-onboarding platform for **Acme Inc.**, a managed service provider that onboards ~300 staffing suppliers a year across 40+ client programs. Today the process is email + spreadsheets + a legacy e-sign tool, takes 3–6 weeks per supplier, and has twice let suppliers work on expired insurance certificates.

Build constraints: **2 focused days**, **$75 of Claude Code credits**. The evaluation rewards a small number of genuinely finished flows over a long feature list.

### The five core features (all required)

1. **Supplier intake** — invite → register → branded external company-profile flow.
2. **Document collection, end to end** — per-supplier checklist, uploads, ops approve/reject with reasons, clear status on both sides. E-signature may be simulated (typed name + timestamp + audit record).
3. **Ops pipeline dashboard** — every supplier by stage, what each is blocked on, drill-down to a full record with activity history.
4. **Roles, auth, admin** — real auth, ops and supplier roles, admins invite users and manage access without developer help.
5. **Notifications** — key events send email, or land in a credible, inspectable outbox: supplier invited, document rejected, onboarding completed.

### Personas to build for

- **Dana Whitfield** — VP, Supplier Management. Wants compliance exposure and cycle time at a glance, with an audit trail. Will judge the app by whether she'd show it to her own clients.
- **Marcus Lee** — Supplier Operations Lead. Lives in the tool daily with a four-person team. Needs the pipeline view.
- **Program managers** — read-only visibility into their programs.
- **Suppliers** — external, ranging from national firms to two-person agencies. The experience carries Acme's brand.

---

## Product principles

- **No dead ends.** Every screen has a real empty state, a sensible default, and an obvious next action.
- **Status is never ambiguous.** Both ops and the supplier always see the same truth about what is done, pending, or blocked.
- **Every state change is an event.** The activity log is a feature, not plumbing — it is what Dana hands to an auditor.
- **Sensitive by default.** Tax and banking documents are never publicly addressable; access is authorized per request.
- **Copy is product.** Write UI text the way a careful ops lead would; no lorem, no placeholder labels.

---

## Local development

```bash
docker compose up -d                 # PostgreSQL 17 on 127.0.0.1:5432
cd backend && ./gradlew test         # unit tests + schema tests (Testcontainers)
```

Two environment facts that cost time when rediscovered:

- **Port 8080 is taken by the Apache that ships with macOS.** Run the backend on another port — `./gradlew bootRun --args='--server.port=8085'` — or stop Apache with `sudo apachectl stop`.
- **Config lives in `application.yml`, and there must be no `application.properties`.** Spring gives `.properties` precedence, so a stray one shadows the YAML silently — the app starts, reads different settings than the file you are editing, and nothing warns you.

The compose credentials (`acme_onboarding` / `acme` / `acme`) match the local defaults in `application.yml`. Every deployed environment overrides them through `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD`.

---

## Conventions

- TypeScript `strict`. No `any` without a written justification.
- Domain rules live in the domain layer, not in components or route handlers.
- Comments explain *why*, not *what*. Prefer a clear name over a comment.
- Document dates (`issuedOn`, `expiresOn`) are calendar facts stored as `DATE`; event timestamps are instants stored as `timestamptz` in UTC and rendered in the viewer's locale. Never conflate the two — see [architecture.md](docs/architecture.md) § Dates, expiry and time zone.
- Compliance status is always computed from the current date, never stored. A stored status becomes wrong at midnight with nothing having written to the row.
- Uploads are capped at 10 MB, restricted to PDF/PNG/JPEG, and validated by magic bytes. **v1 does not scan for malware** — this is a documented, accepted gap, not an oversight.
- Money, dates, and statuses have one formatting helper each — never inline.

---

## Architecture

Full detail in [docs/architecture.md](docs/architecture.md). The short version:

- **`backend/`** — Kotlin 2.3 on JDK 21, Spring Boot 4.1, Gradle Kotlin DSL. Layered `domain / application / adapter`, with domain rules free of framework types. Flyway migrations. Deployed to Cloud Run via Jib.
- **`frontend/`** — React 19 + Vite + TypeScript (strict), **MUI (Material Design) under a custom theme**, TanStack Query. Deployed to Firebase Hosting, which rewrites `/api/**` to Cloud Run so the app is same-origin — no CORS, and the session cookie stays `HttpOnly; Secure; SameSite=Lax`.
- **Never ship stock Material.** Default MUI reads as a Google product, and the brief judges whether suppliers associate the experience with *Acme's* brand. Four levers are always set: a non-Roboto typeface, a custom palette with semantic compliance colors, tightened shape, and compact density with borders over shadows. The supplier portal and ops console use different themes over the same components.
- **Contract** — the backend publishes OpenAPI; the frontend generates its typed client from that spec. A breaking backend change fails the frontend type-check, not the browser.
- **Data** — Cloud SQL for PostgreSQL. Documents in a private Cloud Storage bucket, served only as short-lived V4 signed URLs. Secrets in Secret Manager. Expiry sweeps, the outbox drain and the VMS sync driven by Cloud Scheduler hitting OIDC-authenticated internal endpoints — three jobs, exactly the always-free allowance. Everything runs inside GCP always-free allowances except Cloud SQL, which is a deliberate paid choice and is torn down after submission.
- **Administration** — two separate surfaces: Acme staff administration (`ADMIN` only) and supplier user management (ops, scoped to one supplier). Never the same screen. Lockout safeguards: nobody removes their own admin role, and the last admin cannot be removed.
- **Auth** — server-side sessions (opaque token in an `HttpOnly` cookie) so an admin can revoke access instantly, which is what "manage access without developer help" actually requires.

- **VMS integration** — the client called this critically important, so it is core, not a stretch. Assignments are **pulled** from Acme's VMS and kick off onboarding with no ops action; outcomes — activation, compliance state, COI expiry — are **written back** to it. The VMS is the system of record for the supplier relationship; this tool is the system of record for onboarding evidence. Reliability reuses the transactional outbox, every pull and push is visible at `/ops/integrations`, and Restricted fields (tax ID, bank account) are never transmitted. v1 ships a `VmsConnector` port with a simulated adapter — a real vendor connector is a second implementation, not a redesign. See [architecture.md](docs/architecture.md) §5.
- **Governance** — Acme is an MSP, so their enterprise clients audit *them*; the bar is what survives a client-run audit, not what satisfies Acme internally. Document types carry a classification that drives handling. The audit log is hash-chained and the app's DB role has no `UPDATE`/`DELETE` on it. Document *access* is audited, not only state changes. Suppliers are deactivated, never hard-deleted — content can be purged, the record of it cannot.

AI appears in two places, and only one of them is a stretch goal. **Criteria-based review is client-requested and therefore core**: each program requirement carries an optional reference document and a versioned list of acceptance criteria that Acme writes in plain English, and every submission comes back as a per-criterion `PASS`/`FAIL`/`UNCLEAR` checklist with evidence — a failed criterion becomes a one-click rejection in Acme's own words. **AI field extraction** is the stretch goal, scoped to certificates of insurance, whose expiry dates feed the **compliance engine** (the other stretch goal: expiry dates, upcoming-expirations view, reminder emails). The W-9 path stays **disabled by default**, because a W-9 carries a taxpayer ID and routing it to a third-party API is Acme's decision to make, not ours. All of it is advisory — the model prefills and flags, a human approves.
