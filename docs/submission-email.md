# Submission email — draft

Not sent. Fill the three bracketed values, then send it yourself.

- **To:** rabiya@assembly-industries.com
- **Subject:** FDE build assessment — Acme supplier onboarding — Oscar Valverde

---

Hi Rabiya,

Here is my submission for the Acme Inc. supplier onboarding assessment.

**App:** https://assemblytest.web.app
**Repo:** [REPO URL]
**Loom:** [LOOM URL]

The sign-in screen lists every demo account, so you can drive it without me. The password for all of
them is `Onboarding2026!`.

- **Marcus Lee** (`marcus.lee@acme-msp.example`) — supplier operations. The pipeline and the review
  queue; this is the one to start with.
- **Dana Whitfield** (`dana.whitfield@acme-msp.example`) — administrator. Adds staff administration
  and the auditor export.
- **Priya Raman** (`priya.raman@acme-msp.example`) — program manager, read-only, scoped to one
  program.
- **Alicia Moore** (`alicia.moore@lakesidemed.example`) — a supplier mid-flow, in two programs with
  one mostly pre-filled.
- **Jean Pike** (`jean.pike@cedargrove.example`) — a supplier with a rejected certificate and a way
  to replace it.

The decision memo is `docs/decision-memo.md` in the repo. Two notes on what you will find:

I treated the VMS integration as core rather than as a stretch goal, because the answer to my third
question called it critically important. It runs in both directions against a simulated VMS, with
the retry and dead-lettering visible at `/ops/integrations`.

The model is live on the deployed instance, so criteria prefill and certificate field extraction
both work when you click them. Sending a W-9 to a model is off behind a setting Acme owns — the
reasoning is in the memo, and the short version is that the taxpayer ID is never extracted whatever
that setting says.

Email delivery [is switched on and sends from a demo mailbox / is off, and the outbox at
`/ops/outbox` shows every message exactly as a recipient would read it — choose one before sending].

Thanks for a genuinely good exercise. The brief did the thing good briefs do: the constraints were
the interesting part.

Oscar
