# Decision memo — Acme supplier onboarding, v1

**To:** Dana Whitfield, VP Supplier Management · **From:** Oscar Valverde
**Re:** What I built, what I cut, and what I need from you · **Scope:** two focused days

*Design detail and the full decision record live in [architecture.md](architecture.md); this is the
short version.*

---

## What matters most, and what runs today

Three things went wrong before: onboarding takes three to six weeks, suppliers experience it as
email into a void, and twice a supplier worked on an expired certificate. Everything I built attacks
one of those; everything I cut failed to.

End to end today: a supplier is invited, registers, and sees a per-programme checklist of what Acme
needs and why — what is already on file shown rather than hidden, so a second programme opens mostly
green. Marcus reviews from a queue ordered by wait time and hands a document back with a reason the
supplier reads verbatim; approving the last item activates the programmes. A nightly sweep chases
expiring certificates, assignments pull from the VMS and outcomes are written back, and every state
change is an event you can export for an auditor.

## The decisions worth your attention

**Compliance is computed, never stored.** A `COMPLIANT` flag becomes a lie at midnight with nothing
having touched the row, so status is derived from today's date in your business time zone on every
read. This is the direct answer to the certificate that lapsed twice: there is no state where the
database says compliant and the calendar disagrees.

**The audit log is hash-chained.** Each event carries the hash of its predecessor, so tampering is
*detectable* rather than merely forbidden — and you run that check yourself from the audit screen.
Your clients audit you, and "we restricted access" is the weaker answer. Document *reads* are
audited too; no bucket permission answers "who opened this banking form".

**The acceptance criteria are yours, not mine.** You asked for reference documents plus criteria you
input, which is a better answer than the three rejection reasons I asked for: a seeded catalog
encodes what I guessed on the day I guessed it, criteria encode what you actually require, per
program and versioned. The payoff is the rejection — *"the aggregate shows USD 1,000,000; this
program requires USD 2,000,000"* instead of *"rejected — incorrect information"*. Three rounds of
email is where the cycle time actually goes.

**Half of that shipped, and I want to be exact about which half.** Criteria are stored, versioned,
checked at review and quoted into rejections — all of that works, and each programme carries its own
minimum. What has no screen yet is you *entering* them: they are set up with the programme, so
changing one still comes back to me. That is the gap between what you asked for and what runs, it is
first on the v2 list, and it is frontend work against an endpoint that already exists.

**The taxpayer ID never leaves; the W-9 leaves only if you say so.** The number is encrypted, masked
to four digits, never sent anywhere — enforced in code, with no setting, because there is nowhere in
the system for a second copy to land. Whether the *document* goes to a model is a governance call,
so it is a setting you own rather than a refusal you would need a release to lift.

## E-signature: how I would productionize it against your 500/month cap

Signing produces a real artifact, not a checkbox: a generated PDF carrying the typed name,
timestamp, signer identity, originating IP and the SHA-256 of the exact template text. That is what
an auditor asks for.

**The cap is probably not your binding constraint, and that changes the answer.** You onboard ~300
suppliers a year — about 25 master agreements a month against a 500-envelope cap, five percent of
your contract. If you are near the ceiling, envelopes are going to documents that do not need vendor
weight.

So I would **tier it rather than buy more envelopes**:

- **Master supplier agreement → a vendor envelope.** One document per supplier, real legal exposure,
  worth the per-signature cost. ~25/month.
- **Everything else — addenda, attestations, acknowledgements → the in-product signature.** Under
  ESIGN/UETA a typed name with demonstrated intent and a tamper-evident trail is legally effective
  between businesses. A vendor buys dispute defensibility and identity assurance, not validity, and
  for an addendum to an already-executed master agreement that is usually not worth paying for.

Implementation is an adapter behind the port that already exists plus a webhook — about a week, no
schema change. I expect tiering keeps you inside your current contract rather than upgrading it.

## Time and budget

Two long days: 12:23–23:45, then 07:10–19:27. Somewhere above twenty focused hours. Approximately:

| | |
|---|---|
| **~40%** | The five core features — intake, documents end to end, pipeline, roles and admin, notifications |
| **~15%** | VMS integration and criteria-based review — the two things you called critical, treated as core rather than stretch |
| **~15%** | Stretch: compliance engine, then certificate field extraction |
| **~20%** | Deploy, and then finding six defects that only appear in a deployed environment |
| **~10%** | This memo, the demo, and rehearsing it — which is how three of those six were found |

Claude Code credits: **the full $75.** Worth saying where the last of it went, because it was not
features. It went on deploying, and then on hunting for what only breaks once deployed and on
rehearsing the demo click by click. That found six defects no test could have caught. Three of them
mattered: a clean database silently skipped its seeding, so anyone starting this from scratch met an
empty application; a malformed API key was written into the logs in plain text; and the acceptance
criteria — your own answer 2 — were seeded by a migration that ran before the programmes existed, so
they were invisible in the product built for them.

If I had stopped when the features were done I would have handed you something that looked finished
and greeted you with an empty screen. Spending the last of the budget there rather than on a sixth
feature is the allocation decision I would defend most firmly.

## What is running, and what is a setting

Both things that were a credential away are on: **the model is live**, so criteria prefill and
certificate extraction work when your team clicks them, and **email is delivered** from a
demonstration mailbox — while `/ops/outbox` still shows every message as its recipient reads it.
One thing stays off and is yours to turn on: sending a **W-9** to the model. See question 4.

## What v2 looks like

1. **The screen where you write the criteria**, and one for programs and their document
   requirements alongside it. This is the half of your answer 2 that did not ship, the storage and
   versioning behind it are already done and tested, and it is the shortest item on this list.
2. **The real VMS connector.** Everything except the vendor adapter is built and exercised —
   idempotent pull, retry with backoff, dead-lettering, conflict flagging. Give me credentials and a
   field map.
3. **Reporting for your QBR** — cycle time and funnel by programme and stage. A reading of the audit
   log rather than new instrumentation, because every event is already recorded.
4. **Per-programme document decisions**, if one certificate must be accepted for Meridian and
   rejected for Northstar at once. Today it carries one decision. See question 3.
5. **Hardening**: separate the database role that migrates from the one that serves, malware
   scanning ahead of storage, a penetration test.
6. **SSO through Entra ID**, which you said comes later.

## What I need from you

1. **Which VMS, and who owns the field map?** And when its legal name and an approved W-9 disagree,
   which wins? Today I flag it and keep both, because one is wrong and a person should say which.
2. **Does an expired certificate stop a placement?** Today it reopens document collection and flags
   the supplier; it does not suspend them. That is your policy call, not mine to guess.
3. **Must one certificate clear the strictest programme?** Northstar wants USD 2M, Meridian 1M. I
   assumed clearing the stricter clears both. Say so if not.
4. **Who signs off on sending a W-9 to a model, and what is your retention period per document
   type?** Retention ships deliberately unset — that is your counsel's answer, and guessing would be
   worse than asking.

## Honest risks

- **The VMS integration is proven against a simulation, not a vendor.** What it demonstrates is the
  contract and the failure handling, both visible at `/ops/integrations`.
- **Two days of hardening is two days**, and malware scanning is absent — uploads are capped,
  type-restricted and validated by magic bytes rather than by filename. Both are documented gaps.
- **Nobody has used this daily.** Marcus's queue is ordered by wait time because that is the number
  his team is measured on. That survives contact with his team or it does not.
- **Six defects surfaced in the last stretch.** They are fixed and written up, but the rate at which
  rehearsal found them says the honest thing about a two-day build: it has been exercised, not
  hardened.
