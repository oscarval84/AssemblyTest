# Local development

> Language rule: this repository is English-only. See [CLAUDE.md](../CLAUDE.md), Rule #0.

Production runs on Cloud SQL (see [architecture.md](architecture.md) § Technology choices). For local work the
database is a Postgres container defined in [docker-compose.yml](../docker-compose.yml) at the repository root —
same major version, same encoding and collation, so a query that behaves here behaves there.

## Requirements

- Docker Desktop
- JDK 21
- Node 20+

## Start the database

```bash
docker compose up -d
```

The container is `acme-postgres`, listening on `127.0.0.1:5432` — loopback only, so the development database is
never reachable from the network. Data lives in the named volume `acme-onboarding_postgres-data` and survives
`docker compose down`.

| Setting  | Value             |
| -------- | ----------------- |
| Database | `acme_onboarding` |
| User     | `acme`            |
| Password | `acme`            |

These credentials are local-only and intentionally in version control; they match the defaults in
[application.yml](../backend/src/main/resources/application.yml), so nothing has to be exported to run the
backend. Deployed environments override `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD` from Secret
Manager.

The server runs in UTC and the database was initialised with the `C` collation. Both are deliberate: event
timestamps are UTC instants, and a locale-dependent sort order would make `ORDER BY` differ between a
developer's machine and Cloud SQL.

## Run the backend

```bash
cd backend && ./gradlew bootRun
```

Flyway applies the migrations on startup, so a fresh volume becomes a current schema with no extra step.

macOS ships Apache on port 8080, and it is running on this machine. Either stop it (`sudo apachectl stop`) or
start the backend elsewhere:

```bash
cd backend && ./gradlew bootRun --args='--server.port=8085'
```

## Run the frontend

```bash
cd frontend && npm install && npm run dev
```

Vite serves <http://localhost:5173> and proxies `/api` to the backend on 8085, so development is same-origin
exactly as production is behind Firebase Hosting. That is what keeps the session cookie `SameSite=Lax` and CORS
out of the picture; a proxy here rather than permissive headers is the point.

The backend must be on 8085 for the proxy to find it — see the port note above.

## Sign in

On an empty database the backend seeds a demo world at startup and logs the shared password. The sign-in screen
lists the accounts, and clicking one fills the form:

| Role                 | Email                                | What they see                                              |
| -------------------- | ------------------------------------ | ---------------------------------------------------------- |
| Administrator        | `dana.whitfield@acme-msp.example`    | Everything, plus staff administration and the demo reset    |
| Supplier operations  | `marcus.lee@acme-msp.example`        | The pipeline and the review queue                           |
| Program manager      | `priya.raman@acme-msp.example`       | Read-only, Northstar Health System only                     |
| Supplier             | `alicia.moore@lakesidemed.example`   | Two programs, the second mostly pre-filled                  |
| Supplier             | `jean.pike@cedargrove.example`       | A rejected certificate, with the reason                     |

The password is `Onboarding2026!` for all of them. Seeding is controlled by `acme.demo.seed-on-startup`
(`DEMO_SEED`), which also gates the admin-only reset — both are dark in an environment holding real data.

## Regenerate the API client

The frontend's types come from the backend's own OpenAPI document, and the generated file is committed so a
build never depends on a running backend. After changing a controller or a DTO:

```bash
cd frontend && npm run generate:api
```

It needs the backend running on 8085. A breaking backend change then fails `npm run typecheck` rather than
failing in a supplier's browser.

## Inspect the database

An Adminer instance is available behind an opt-in profile, so it never starts as part of the normal `up`:

```bash
docker compose --profile tools up -d
```

It serves <http://localhost:8081> and defaults to the `postgres` host. A `psql` session inside the container
works too, and prints timestamps in UTC:

```bash
docker exec -it acme-postgres psql -U acme -d acme_onboarding
```

## Reset

```bash
docker compose down -v && docker compose up -d
```

`-v` drops the volume, so the next start is an empty database that Flyway migrates from scratch and re-seeds.
An administrator can also restore the demo world from inside the app, which is faster and does not restart
anything.

Uploaded files live outside the database, under `backend/.local-storage`, and are not dropped by the command
above. Delete that directory alongside the volume for a genuinely clean slate.

## Note on database roles

Production restricts the application's role to `INSERT` and `SELECT` on `activity_event` so the audit chain
cannot be rewritten ([architecture.md](architecture.md) § Audit log). Locally there is a single role that owns
everything, and `V2__audit_append_only.sql` skips those grants when the `appDbRole` placeholder is empty. The
append-only trigger in the same migration binds every role including the owner, so the guarantee still holds
here — what is missing locally is only the defence-in-depth layer, not the enforcement.
