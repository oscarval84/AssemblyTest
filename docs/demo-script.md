# Demo script

Twelve minutes, four sign-ins, one thread: a supplier arrives, gets stuck, gets unstuck, and leaves
an audit trail. Every account uses the password `Onboarding2026!`, and the sign-in screen lists
them so nobody is stuck at the door.

Before you start: `docker compose up -d`, the backend on 8085, the frontend on 5173. The demo world
seeds itself on an empty database. To rewind between runs, sign in as Dana and use **Reset the demo
data** in the account menu.

---

## 0. Set the scene (30 seconds)

> Acme onboards about 300 staffing suppliers a year across 40-odd client programs. Today it is
> email, spreadsheets, and a legacy e-sign tool; it takes three to six weeks, and twice a supplier
> worked on an expired insurance certificate. Here is what that looks like when the tool knows what
> it is doing.

---

## 1. Marcus opens his queue — *"what is Acme sitting on?"* (2 min)

Sign in as **marcus.lee@acme-msp.example** (Supplier operations).

- The pipeline is grouped by **who has to act next**, not by stage. "Waiting on Acme" is his team's
  queue and the number cycle time is measured on; "waiting on the supplier" is everyone he has to
  chase; "clear" is nobody.
- Point at one row's **blocked on** column: *3 documents awaiting review*, *invitation not yet
  accepted*, *renewal due by 31 August*. Every row says exactly what is missing, so a status
  meeting becomes a screen.
- Open **Review queue**. Ordered oldest-first by wait time.

> Nothing here was typed by a person. Every one of these is derived from documents and dates.

---

## 2. A review, with Acme's own criteria — *the cycle-time fix* (3 min)

From the review queue, open the pending **Certificate of Insurance** for Beacon Technical Services.

- The acceptance criteria sit beside the document — **Acme wrote these**, per program, versioned,
  with no deploy. Marcus adds one the first time a supplier gets something wrong and every
  submission after that is checked against it.
- Mark one criterion **Fail** and add what the document showed
  (`General liability aggregate: USD 1,000,000`).
- Click **Reject using this**. The reason is the criterion's own wording, and the note carries the
  evidence.

> The supplier is not told "rejected — incorrect information". They are told the aggregate shows one
> million and this program needs two. That is the difference between one resubmission and three,
> and three rounds of email is where the three-to-six-week cycle time actually goes.

*(If a model is configured, two buttons appear on this dialog. **Ask the model** prefills the whole
checklist — a verdict and a quoted span per criterion. **Read the fields** reads the certificate
itself and compares it with the expiry date the supplier typed, the program's coverage minimum, and
the supplier's own record; where the certificate disagrees about expiry, one click applies its date
and the change is recorded with both values. A FAIL never rejects, a PASS never approves, and
nothing rewrites a date on its own — it saves reading time, not judgement. With no key configured
neither button is there, and review works the same way.)*

Open **Notifications**. The rejection email is there, in full, as the supplier will read it.

> Delivery is switched off in this environment and the screen says so rather than claiming messages
> were sent. Turning it on is an SMTP credential.

---

## 3. The supplier's side — *no dead ends* (2 min)

Sign in as **jean.pike@cedargrove.example** (Supplier).

- Same truth, different audience: the rejected document says what to replace and why, in the words
  Marcus used. Nothing here says "contact your account manager".
- The checklist splits **what is already on file** from **what is new for this program** — a supplier
  joining a second program does not re-upload their W-9, because supplier-scope documents satisfy
  every program at once.
- Show the upload dialog for an expiring document: the expiry date is asked for because it is what
  drives the reminder, and the copy says so.

---

## 4. Expiry, the failure that actually cost them (2 min)

Back as Marcus, open **Expiring**.

- Already expired, within 30 days, within 60. The nightly sweep reminds the supplier at 30 days, at
  7 days, and the morning after — each claimed once per document version, so a certificate sitting
  in the warning band for a month produces three emails rather than thirty.
- An expired certificate reopens document collection and writes the transition to the supplier's
  timeline. It flags rather than blocks: whether that stops a placement is the VMS's call, and it
  is an open question for Acme.

> Compliance is computed from today's date on every read. There is no state where the database says
> compliant and the calendar disagrees — which is precisely how a supplier ended up working on a
> lapsed certificate twice. The remaining hole was the date itself: a supplier types it at upload and
> nobody checked it against the document. That is what certificate extraction closes.

---

## 5. The VMS, in both directions (1.5 min)

Open **VMS**.

- Inbound: assignments pull in and start onboarding with no ops action — the supplier appears in
  Marcus's pipeline already at the right stage with the right checklist.
- Outbound: activation and compliance changes are queued in the same transaction as the change and
  pushed with backoff, dead-lettering after six attempts rather than looping quietly.
- Note the footer: tax ID and bank account are Restricted and never transmitted.

> This is a working integration against a simulated VMS. What it demonstrates is the contract and
> the failure handling; a real connector is a second implementation of the same port.

---

## 6. Dana's question — *"can I hand this to an auditor?"* (2 min)

Sign in as **dana.whitfield@acme-msp.example** (Administrator).

- Open a supplier record and scroll to **Activity**: every state change, who made it, when.
- Click **Export this history** → the audit export, pre-filtered to that supplier.
- Choose the supplier and point at **Chain integrity**: *unbroken, N events, each carrying the hash
  of the one before it*. Then **Download CSV** (for analysis) or **Download PDF** (for the audit
  response).

> The log is append-only and hash-chained, and the application's database role cannot update or
> delete a row in it. Tampering is detectable, not merely forbidden. Taking a copy is itself
> recorded — that event will be in the next export.

Finish on **Staff access**: invite, change a role, adjust program scope, deactivate, send a reset.
Two safeguards worth naming out loud — nobody can remove their own admin role, and the last
administrator cannot be removed.

> Supplier users are managed inside the supplier record instead. Two surfaces on purpose: keeping
> them apart is what stops an ops user granting Acme-internal access while working a supplier's file.

---

## 7. Read-only, scoped (30 seconds)

Sign in as **priya.raman@acme-msp.example** (Program manager).

- Only Northstar Health suppliers. No review queue, no administration, and the audit export she can
  reach covers her programs and says so.

> Program scoping lives in a table rather than in a token claim, which is why it can be changed by
> an admin without a deploy — and why revoking it takes effect on the next request.

---

## Close

> Two days. Five core flows finished rather than fifteen started, plus both stretch goals. Two
> things are switched off and both are a credential rather than a build: email delivery, and the
> model. The decision memo lists what we cut, what it would take to add, and the five questions we
> need Acme to answer.
