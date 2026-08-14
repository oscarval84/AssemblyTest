# Deployment

> Language rule: this repository is English-only. See [CLAUDE.md](../CLAUDE.md), Rule #0.

Firebase Hosting serves the built SPA and rewrites `/api/**` to Cloud Run, so the browser only ever
talks to one origin — no CORS, and the session cookie stays `HttpOnly; Secure; SameSite=Lax`
([architecture.md](architecture.md) §1). Cloud SQL holds the data, a Cloud Storage bucket holds the
documents, Secret Manager holds the secrets, and Cloud Scheduler drives the three jobs.

Everything below is a one-time setup except the last section, which is the repeatable deploy.

**Substitute your own values.** The commands use these names throughout:

```bash
export PROJECT_ID=acme-supplier-onboarding      # your project
export REGION=us-central1                       # Firebase Hosting can only rewrite to
                                                # a Cloud Run service in a supported region
export SERVICE=supplier-onboarding              # must match firebase.json
export SQL_INSTANCE=onboarding-db
export BUCKET=${PROJECT_ID}-documents
```

---

## 0. What you do, and what the tooling does

Three things in this runbook involve a credential, and they are yours to type: the billing details on
the account, the database password, and the values you paste into Secret Manager. Nothing in this
repository stores any of them, and no automation here reads them back.

Everything else is `gcloud` and `firebase`, which authenticate as you.

---

## 1. Account, project, billing

Create the account and project in the console — [console.cloud.google.com](https://console.cloud.google.com).
Billing has to be enabled even though the bill is cents: Cloud SQL is the one component with no
always-free tier, which is a deliberate choice the memo explains.

A new account starts on the 90-day trial with credit attached. The *design* sits inside permanent
always-free allowances (§9) rather than on trial credit — but the account itself is on trial until
you convert it, and everything stops when the trial ends. Convert it or tear the project down before
that happens; § Teardown is the second option.

Then point the CLI at it:

```bash
gcloud auth login
gcloud config set project $PROJECT_ID
gcloud config set run/region $REGION
```

## 2. Enable the APIs

```bash
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudscheduler.googleapis.com \
  storage.googleapis.com
```

## 3. Artifact Registry, for the container image

```bash
gcloud artifacts repositories create acme \
  --repository-format=docker \
  --location=$REGION \
  --description="Acme supplier onboarding images"

gcloud auth configure-docker ${REGION}-docker.pkg.dev
```

## 4. Cloud SQL

```bash
gcloud sql instances create $SQL_INSTANCE \
  --database-version=POSTGRES_17 \
  --edition=ENTERPRISE \
  --tier=db-f1-micro \
  --region=$REGION \
  --storage-size=10GB \
  --no-backup

gcloud sql databases create acme_onboarding --instance=$SQL_INSTANCE
```

Provisioning takes a few minutes. Then create the application's database user — **you type this
password, and it should not be one you have used anywhere else**:

```bash
gcloud sql users create acme --instance=$SQL_INSTANCE --prompt-for-password
```

`--prompt-for-password` is deliberate: passing `--password=…` puts the secret in your shell history.

The instance connection name is what Cloud Run needs, and it is not the instance name:

```bash
gcloud sql instances describe $SQL_INSTANCE --format='value(connectionName)'
# → PROJECT_ID:REGION:onboarding-db
```

**The audit log's grants.** `V2__audit_append_only.sql` revokes `UPDATE` and `DELETE` on
`activity_event` from the application's role, but only when Flyway is told which role that is. Set
`APP_DB_ROLE=acme` in step 7 or the migration logs a notice and skips the grant — the append-only
trigger still holds, but the defence-in-depth layer is not there.

One role does both jobs here, which is worth knowing rather than discovering later: `acme` applies
the migrations *and* serves the application, so it owns `activity_event`. The revoke is real — a
non-superuser owner is refused `UPDATE` — but an owner can grant the privilege back to itself and
can disable its own triggers, so this is a guardrail rather than a wall. Making it a wall is two
roles: an owner that only ever runs Flyway, and a runtime user that owns nothing. That is a change
to this runbook, not to the application.

## 5. The documents bucket

```bash
gcloud storage buckets create gs://$BUCKET \
  --location=$REGION \
  --uniform-bucket-level-access \
  --public-access-prevention
```

Both flags matter and neither is a default: uniform access means no per-object ACL can make a W-9
readable, and public access prevention means no future misconfiguration can either.

**How documents are read, and a correction to what §7 describes.** The architecture describes a GCS
adapter serving short-lived V4 signed URLs. That adapter is not built — `LocalDocumentStore` is the
only implementation of the port. What ships instead is the bucket **mounted into Cloud Run as a
volume**, with the filesystem adapter writing to it: durable, shared across instances, and no code
change.

That is not only the expedient answer, and it is worth saying why. A signed URL is a bearer
credential that works for anyone holding it until it expires; streaming through
`/api/documents/{id}/download` means **every read is authorized and audited at the moment it
happens**, which is what "access is authorized per request" in CLAUDE.md actually asks for. The
signed-URL path remains the right answer at a scale where streaming bytes through the application is
a cost problem. At fifteen internal users it is not.

## 6. Secrets

Create each secret empty, then paste the value. **Every one of these is yours to paste** — run the
command, paste, then `Ctrl-D`:

```bash
for s in database-password field-encryption-key field-encryption-salt jobs-token; do
  gcloud secrets create $s --replication-policy=automatic
done

gcloud secrets versions add database-password --data-file=-        # the password from step 4
gcloud secrets versions add field-encryption-key --data-file=-     # a long random string
gcloud secrets versions add field-encryption-salt --data-file=-    # 16 hex characters
gcloud secrets versions add jobs-token --data-file=-               # a long random string
```

Generate the three random ones locally rather than inventing them by hand:

```bash
openssl rand -base64 32     # field-encryption-key, and jobs-token
openssl rand -hex 8         # field-encryption-salt — hex, and exactly 16 characters
```

The salt must be hex: `Encryptors.stronger` decodes it, and a non-hex value fails at startup rather
than at first use.

Add the Anthropic key and the SMTP password the same way when you have them:

```bash
gcloud secrets create anthropic-api-key --replication-policy=automatic
gcloud secrets versions add anthropic-api-key --data-file=-
```

## 7. Service account and deploy

The service runs as its own account with only the roles it needs — never the default compute
account, which is project editor:

```bash
gcloud iam service-accounts create onboarding-run \
  --display-name="Supplier onboarding (Cloud Run)"

export SA=onboarding-run@${PROJECT_ID}.iam.gserviceaccount.com

for role in roles/cloudsql.client roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID --member="serviceAccount:$SA" --role="$role"
done

# Object access on one bucket, not project-wide storage access.
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:$SA" --role=roles/storage.objectAdmin
```

Build and push the image. Jib needs no Dockerfile and no local Docker daemon:

```bash
cd backend
./gradlew jib -Pgcp.project=$PROJECT_ID -Pgcp.region=$REGION
cd ..
```

Then deploy. This is the long one, and every flag earns its place:

```bash
export SQL_CONNECTION=$(gcloud sql instances describe $SQL_INSTANCE --format='value(connectionName)')

gcloud run deploy $SERVICE \
  --image=${REGION}-docker.pkg.dev/${PROJECT_ID}/acme/supplier-onboarding:latest \
  --region=$REGION \
  --service-account=$SA \
  --allow-unauthenticated \
  --add-cloudsql-instances=$SQL_CONNECTION \
  --add-volume=name=documents,type=cloud-storage,bucket=$BUCKET \
  --add-volume-mount=volume=documents,mount-path=/mnt/documents \
  --memory=1Gi \
  --cpu=1 \
  --min-instances=0 \
  --max-instances=4 \
  --set-env-vars="DATABASE_URL=jdbc:postgresql:///acme_onboarding?cloudSqlInstance=${SQL_CONNECTION}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&user=acme" \
  --set-env-vars="DATABASE_USER=acme,APP_DB_ROLE=acme" \
  --set-env-vars="STORAGE_LOCAL_PATH=/mnt/documents" \
  --set-env-vars="SESSION_COOKIE_SECURE=true,BUSINESS_TIME_ZONE=America/New_York" \
  --set-env-vars="PORTAL_BASE_URL=https://${PROJECT_ID}.web.app" \
  --set-secrets="DATABASE_PASSWORD=database-password:latest" \
  --set-secrets="FIELD_ENCRYPTION_KEY=field-encryption-key:latest" \
  --set-secrets="FIELD_ENCRYPTION_SALT=field-encryption-salt:latest" \
  --set-secrets="JOBS_TOKEN=jobs-token:latest"
```

`--allow-unauthenticated` is required, not lax: Firebase Hosting invokes the Cloud Run service as an
anonymous caller, so a private service cannot be rewritten to. The application's own session auth is
the gate, and `/internal/jobs/*` is additionally behind the `X-Job-Token` shared secret — which is
why that token is a real secret and not a convenience.

**`DEMO_SEED` is left at its default of true**, which seeds the demo world on an empty database. That
is right for an evaluated demo and wrong for anything holding real supplier data; set
`DEMO_SEED=false` before this ever sees a real supplier.

## 8. The frontend

The SPA is same-origin with the API, so it needs no environment configuration — it calls `/api/…`
and Hosting rewrites it.

```bash
firebase login
firebase use --add            # select $PROJECT_ID, name the alias "default"

cd frontend && npm ci && npm run build && cd ..
firebase deploy --only hosting
```

The site lands at `https://${PROJECT_ID}.web.app`. If you set `PORTAL_BASE_URL` to something else in
step 7, fix it now — that value is what invitation and reminder emails link to, and a wrong one sends
suppliers nowhere.

## 9. The three scheduled jobs

Cloud Run scales to zero, so an in-process `@Scheduled` job would not run reliably. Cloud Scheduler's
always-free allowance is exactly three jobs, which is what this uses (§5).

```bash
export RUN_URL=$(gcloud run services describe $SERVICE --region=$REGION --format='value(status.url)')
export JOBS_TOKEN=$(gcloud secrets versions access latest --secret=jobs-token)

gcloud scheduler jobs create http outbox-drain \
  --location=$REGION --schedule="*/5 * * * *" \
  --uri="${RUN_URL}/internal/jobs/outbox-drain" --http-method=POST \
  --headers="X-Job-Token=${JOBS_TOKEN}"

gcloud scheduler jobs create http compliance-sweep \
  --location=$REGION --schedule="0 7 * * *" --time-zone="America/New_York" \
  --uri="${RUN_URL}/internal/jobs/compliance-sweep" --http-method=POST \
  --headers="X-Job-Token=${JOBS_TOKEN}"

gcloud scheduler jobs create http vms-sync \
  --location=$REGION --schedule="*/15 * * * *" \
  --uri="${RUN_URL}/internal/jobs/vms-sync" --http-method=POST \
  --headers="X-Job-Token=${JOBS_TOKEN}"
```

The five-minute drain doubles as a keep-warm ping, which is why it is worth more than its schedule
suggests: it costs a rounding error of the free allowance and it means an evaluator opening the app
does not wait on a cold start (§3).

The compliance sweep runs at 07:00 **New York time**, not UTC. Every expiry decision resolves "today"
in Acme's business zone, and a job firing at 02:00 UTC would expire a certificate a day early for the
ops team.

## 10. Optional: turn on what is switched off

Neither is required for the app to be correct — see the memo, § What is not switched on.

```bash
# The model: criteria prefill and document field extraction.
gcloud run services update $SERVICE --region=$REGION \
  --set-secrets="ANTHROPIC_API_KEY=anthropic-api-key:latest"

# Email delivery. The from-address domain must have SPF and DKIM records naming
# the relay, or invitations land in spam and the supplier never arrives.
gcloud run services update $SERVICE --region=$REGION \
  --set-env-vars="SPRING_MAIL_HOST=smtp.example.com,SPRING_MAIL_PORT=587" \
  --set-env-vars="SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true" \
  --set-env-vars="SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true" \
  --set-env-vars="MAIL_TRANSPORT=smtp,MAIL_FROM=no-reply@your-domain.com" \
  --set-secrets="SPRING_MAIL_PASSWORD=smtp-password:latest"
```

W-9 extraction stays off unless Acme decides otherwise, and turning it on is one variable —
`AI_W9_EXTRACTION_ENABLED=true`. That decision, and what has to be in place first, is in the memo.

## Redeploying

```bash
cd backend && ./gradlew jib -Pgcp.project=$PROJECT_ID -Pgcp.region=$REGION && cd ..
gcloud run deploy $SERVICE --region=$REGION \
  --image=${REGION}-docker.pkg.dev/${PROJECT_ID}/acme/supplier-onboarding:latest

cd frontend && npm run build && cd .. && firebase deploy --only hosting
```

Environment variables and secrets persist across deploys; only the image changes.

## Teardown

Cloud SQL is the only component that bills while idle, so this is the one that matters:

```bash
gcloud sql instances delete $SQL_INSTANCE
gcloud run services delete $SERVICE --region=$REGION
gcloud storage rm -r gs://$BUCKET
```

Or delete the project, which takes everything with it:

```bash
gcloud projects delete $PROJECT_ID
```

## What is not here

**A CI/CD pipeline.** The architecture's decision table names GitHub Actions with Workload Identity
Federation, and that remains the right production answer — no long-lived service-account keys
anywhere. It is not built, and this runbook deploys from a developer machine instead. The honest
reason is that nobody asked for it: the assessment brief asks for a deployed URL and says free tiers
are fine, and an hour of IAM federation before the app is reachable buys a reviewer nothing they can
see. The gap is recorded in [build-log.md](build-log.md) rather than papered over.
