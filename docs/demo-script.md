# Demo script

Ten minutes, presented to Dana and Marcus — not to an engineering audience. One thread: a supplier
arrives, gets stuck, gets unstuck, and leaves a trail Dana can hand to an auditor. Optional
**builder's notes** at the end, up to three minutes, for whoever wants the engineering view.

**No code tour.** Nothing in the first ten minutes shows a file, a schema or a terminal.

Run it against **https://assemblytest.web.app**. Every account uses `Onboarding2026!`, and the
sign-in screen lists them, so nobody is stuck at the door.

**Rewinding is local-only, on purpose.** *Reset the demo data* clears the world by truncating, which
reaches the activity log — and the deployed application's database role may append to that table and
nothing else. On the deployed instance the button explains that rather than doing it. Recreating the
database is the way to rewind there; the demo seeds itself on an empty one. Locally the reset works
normally. If you want it as a beat in the recording, it is a good one: an admin being told the
system will not erase its own audit trail is the compliance story demonstrating itself.

---

## 0. Set the scene (20 sec)

> Acme onboards about 300 staffing suppliers a year across 40-odd client programs. Today it is
> email, spreadsheets and a legacy e-sign tool; it takes three to six weeks, and twice a supplier
> worked on an expired insurance certificate. Here is the same week with a tool that knows what it
> is doing.

---

## 1. Marcus opens his queue — *"what is Acme sitting on?"* (1.5 min)

Sign in as **marcus.lee@acme-msp.example**.

- The pipeline is grouped by **who has to act next**, not by stage. "Waiting on Acme" is his team's
  queue and the number cycle time is measured on; "waiting on the supplier" is everyone to chase.
- Point at one **blocked on** cell: *3 documents awaiting review*, *invitation not yet accepted*.
  Every row says what is missing, so the status meeting becomes a screen.
- Open **Review queue** — oldest first, by wait time.

> Nobody typed any of this. It is all derived from documents and dates.

---

## 2. A review, in Acme's own words — *the cycle-time fix* (2.5 min)

Open the pending **Certificate of Insurance** for Beacon Technical Services.

- The acceptance criteria sit beside the document — **this program's criteria**, versioned, so every
  submission is checked against the same list rather than against what a reviewer remembers.

  *Say this as it is: these are set up with the program, and the screen where Marcus maintains them
  himself is the first thing in v2. Do not claim he can add one — he cannot yet, and it is the kind
  of thing a client discovers on day two.*
- Mark one criterion **Fail** and add what the document showed
  (`General liability aggregate: USD 1,000,000`).
- Click **Reject using this**.

> The supplier is not told "rejected — incorrect information". They are told the aggregate shows one
> million and this program needs two. That is the difference between one resubmission and three, and
> three rounds of email is where the three-to-six-week cycle time goes.

**Then show the model, because it is running here.** Click **Read the fields** on a certificate.

> It reads the certificate and disagrees out loud. Your suppliers type the expiry date at upload and
> the whole compliance engine runs on that date — and until now nobody checked it against the
> document. Where they disagree, Marcus sees both dates and applies the correction in one click.
> It never rewrites that date on its own: replacing a mistake nobody checks with a mistake nobody
> can see is not an improvement. A blank field means the certificate does not say — it leaves gaps
> rather than guessing, because a reviewer can check a gap and cannot check a guess.

Open **Notifications**. The rejection email is there in full, as the supplier will read it.

---

## 3. The supplier's side — *no dead ends* (1.5 min)

Sign in as **jean.pike@cedargrove.example**.

- Same truth, different audience: the rejected document says what to replace and why, in Marcus's
  words. Nothing says "contact your account manager".
- The checklist splits **already on file** from **new for this program** — a supplier joining a
  second program does not re-upload their W-9.
- Open the upload dialog for an expiring document: the expiry date is asked for because it drives
  the reminder, and the copy says so.

---

## 4. Expiry — the failure that actually cost them (1.5 min)

Back as Marcus, open **Expiring**.

- Already expired, within 30 days, within 60. The sweep reminds at 30 days, at 7, and the morning
  after — each claimed once per document version, so a month in the warning band is three emails,
  not thirty.
- Expiry reopens document collection and writes the transition to the timeline. It flags rather than
  blocks; whether that stops a placement is your call, and it is one of my open questions.

> Compliance is computed from today's date on every read. There is no state where the database says
> compliant and the calendar disagrees — which is exactly how a supplier ended up working on a lapsed
> certificate twice.

---

## 5. The VMS, in both directions (1 min)

Open **VMS**.

- Inbound: assignments pull in and start onboarding with no ops action — the supplier appears in the
  pipeline already at the right stage with the right checklist.
- Outbound: activation and compliance changes are queued in the same transaction as the change and
  pushed with backoff, dead-lettering rather than looping quietly.
- Tax ID and bank account are Restricted and never transmitted.

> A working integration against a simulated VMS. What it demonstrates is the contract and the
> failure handling; a real connector is a second implementation of the same port.

---

## 6. Dana's question — *"can I hand this to an auditor?"* (1.5 min)

Sign in as **dana.whitfield@acme-msp.example**.

- Open a supplier record, scroll to **Activity**: every state change, who made it, when.
- Click **Export this history** → pre-filtered to that supplier.
- Point at **Chain integrity**: *unbroken, N events, each carrying the hash of the one before it*.
  Then **Download PDF**.

> Append-only and hash-chained. Tampering is detectable, not merely forbidden — and you run that
> check yourself. Taking this copy is itself recorded; it will be in the next export.

Finish on **Staff access**: invite, change a role, deactivate, send a reset — no developer, no
ticket. Two safeguards worth naming: nobody removes their own admin role, and the last administrator
cannot be removed.

---

## 7. Read-only, scoped (20 sec)

Sign in as **priya.raman@acme-msp.example**.

- Only Northstar Health suppliers. No review queue, no administration.

---

## Close (20 sec)

> Two days. Five core flows finished rather than fifteen started, plus both stretch goals and the
> VMS integration you called critical. The memo has what I cut, what it would take to add, and the
> four questions I need you to answer.

---

## Builder's notes — optional, up to 3 min

Only if the engineering audience wants it. Skip entirely if the ten minutes ran long.

- **Compliance is computed, never stored**, and "today" resolves in Acme's business time zone. A
  stored status is wrong at midnight with nothing having written to the row.
- **The audit log is hash-chained and append-only**, enforced by a trigger and by table grants. The
  payloads are stored as `json` rather than `jsonb` — `jsonb` normalises key order, so the bytes
  read back differed from the bytes hashed and every event failed its own verification. A test found
  that, not a review.
- **Ports and adapters where a vendor decision is pending**: the VMS connector, the mail transport,
  the document store and the model all sit behind ports, so each is a second implementation rather
  than a redesign.
- **The W-9 goes to a model only if Acme turns it on** — a setting they own, not a refusal in code,
  because the memo tells them the decision is theirs. What is *not* configurable is that the
  taxpayer ID is never extracted: no field for it anywhere in that path.
- **The last hour was spent hunting for what only breaks in production**, and it found four
  defects — including one where a clean database silently skipped seeding, which is what a reviewer
  would have got, and one where a malformed API key was written into the logs.
