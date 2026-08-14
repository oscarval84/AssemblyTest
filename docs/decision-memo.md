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

End to end today: a supplier is invited, registers, and sees a per-program checklist of what Acme
needs and why — with what is already on file shown rather than hidden, so a second program opens
mostly green. They upload, sign, and watch each item move. Marcus reviews from a queue ordered by
wait time and hands a document back with a reason the supplier reads verbatim; approving the last
item activates the programs. A nightly sweep chases expiring certificates, assignments pull from the
VMS and outcomes are written back, and every state change is an event you can export for an auditor.

## Four decisions worth your attention

**Compliance is computed, never stored.** A `COMPLIANT` flag becomes a lie at midnight with nothing
having touched the row, so status is derived from today's date in your business time zone on every
read. This is the direct answer to the certificate that lapsed twice: there is no state where the
database says compliant and the calendar disagrees.

**The audit log is hash-chained.** Each event carries the hash of its predecessor, so tampering is
*detectable* rather than merely forbidden — and you run the check yourself from the audit screen.
That matters because your clients audit you, and "we restricted access" is the weaker answer.
Document *reads* are audited too; no bucket permission answers "who opened this banking form".

**You write the acceptance criteria, not me.** You asked for reference documents plus criteria you
input, which is a better answer than the three rejection reasons I asked for: a seeded catalog
encodes what I guessed on the day I guessed it, authored criteria encode what you require, per
program, with no deploy. The payoff is the rejection — *"the aggregate shows USD 1,000,000; this
program requires USD 2,000,000"* instead of *"rejected — incorrect information"*. Three rounds of
email is where the cycle time actually goes.

**The taxpayer ID never leaves; the W-9 leaves only if you say so.** The number is encrypted, masked
to four digits, never sent anywhere — enforced in code, with no setting, because there is nowhere in
the system for a second copy to land. Whether the *document* goes to a model is a governance call,
so it is a setting you own rather than a refusal you would need a release to lift.

## E-signature: how I would productionize it against your 500/month cap

Signing produces a real artifact, not a checkbox: a generated PDF carrying the typed name,
timestamp, signer identity, originating IP and the SHA-256 of the exact template text, linked to a
versioned signature record. That is what an auditor asks for.

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

Two days, roughly 17 focused hours: day one 12:23–23:45, day two from 07:10. Approximately:

| | |
|---|---|
| **~55%** | The five core features — intake, documents end to end, pipeline, roles and admin, notifications |
| **~20%** | VMS integration and criteria-based review — the two things you called critical, treated as core rather than stretch |
| **~15%** | Stretch: compliance engine, then certificate field extraction |
| **~10%** | Deploy, and correcting four defects that only appear in a deployed environment |

Claude Code credits: **the full $75.** Worth saying where the last of it went, because it was not
features. It went on deploying and then hunting for what only breaks in a deployed environment,
which found four defects no test could have caught — including one where a clean database silently
skipped seeding, so anyone starting this from scratch would have opened an empty application, and
one where a malformed API key was written into the logs in plain text.

If I had stopped when the features were done I would have submitted something that looked finished
and greeted you with an empty screen. Spending the last of the budget on that rather than on a sixth
feature is the allocation decision I would defend most firmly.

## What v2 looks like

1. **The real VMS connector.** Everything except the vendor adapter is built and exercised —
   idempotent pull, retry with backoff, dead-lettering, conflict flagging. Give me credentials and a
   field map.
2. **Reporting for your QBR.** Cycle time and funnel by program and stage — a reading of the audit
   log rather than new instrumentation, because every event is already recorded.
3. **Per-program document decisions**, if you need one certificate accepted for Meridian and
   rejected for Northstar at once. Today it carries one decision. See question 3.
4. **Hardening**: split the database role that migrates from the one that serves, add malware
   scanning ahead of storage, put it through a penetration test.
5. **SSO through Entra ID**, which you said comes later. The session layer is where it plugs in.

## What I need from you

1. **Which VMS, and who owns the field map?** Also: when the VMS's legal name and an approved W-9
   disagree, which wins? Today I flag it and keep both, because one is wrong and a person should say
   which.
2. **Does an expired certificate stop a placement?** Today it reopens document collection and flags
   the supplier; it does not suspend them. That is your policy call, not mine to guess.
3. **Must one certificate clear the strictest program?** Northstar wants USD 2M and Meridian 1M. My
   assumption is that clearing the stricter clears both. Say so if not.
4. **Who signs off on sending a W-9 to a model, and what is your retention period per document
   type?** The retention field ships deliberately unset — that is your counsel's answer, and guessing
   it would be worse than asking.

## Honest risks

- **The VMS integration is proven against a simulation, not a vendor.** What it demonstrates is the
  contract and the failure handling, both visible at `/ops/integrations`.
- **Two days of hardening is two days**, and malware scanning is absent — uploads are capped,
  type-restricted and validated by magic bytes rather than by filename. Both are documented gaps.
- **Nobody has used this daily.** Marcus's queue is ordered by wait time because that is the number
  his team is measured on. That survives contact with his team or it does not.
