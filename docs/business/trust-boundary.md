# Trust Boundary (Client vs. Server Authority)

> **Area code:** `TRUST` · **Source:** README §0, §6, §7, §10
>
> The single rule every economy-touching feature obeys. If a feature's spec ever implies the **client**
> decides an economy or accident outcome, that spec is wrong. This doc exists so each feature can simply
> reference `TRUST-*` instead of re-litigating authority.

## The boundary in one line

> **The client is a renderer and an input device. The server is the source of truth for anything the user
> can win or lose.** Everything in between is *optimistic* and must reconcile.

## Business rules

| ID | Rule | Authority | Source |
|---|---|---|---|
| TRUST-BR-01 | The client computes **no** economy outcome (currency earned/lost, progress gained/lost, accident result). It may *estimate* for display only. | Server | §0, §10 |
| TRUST-BR-02 | All economy **writes** go through callable functions, verified by Auth + App Check. Direct client writes to economy data are denied by Security Rules. | Server | §0, §8 |
| TRUST-BR-03 | The client **reads** economy/trip state via Firestore listeners for live UI, but reads are never treated as authority to write. | Shared | §8 |
| TRUST-BR-04 | The **Accident roll** (occurrence + severity) executes server-side only. The client never holds the RNG, the risk→chance mapping, or the severity table as authority. | Server | §5 |
| TRUST-BR-05 | When optimistic client state and server result conflict, **the server value wins** and the client reconciles its display to it. | Shared | §4, §10 |
| TRUST-BR-06 | Purchases (tier upgrades, currency packs) are validated server-side against store receipts **before** anything is granted. A client's claim of a purchase is never trusted. | Server | §7 |
| TRUST-BR-07 | Reward/grant/completion operations are **idempotent**, keyed by an Operation ID, so a retried request cannot double-apply. | Server | §6, §10 |
| TRUST-BR-08 | **Server time is authoritative** for anything time-sensitive that has economy meaning: streak day boundaries (a fixed **PH-time / UTC+8** day — `TRUST-MIS-03`), repair/lockout timers, session-duration caps. Client clock is advisory. | Server | new (derived) |
| TRUST-BR-09 | Client-reported quantities that drive reward (e.g. focused-seconds) are **untrusted inputs**: the server bounds/validates them against independently observable facts (e.g. wall-clock between server-stamped trip start and completion, the chosen duration) before granting. | Server | new (derived) |
| TRUST-BR-10 | **Non-economy, per-user data** (e.g. Tasks, profile preferences) **may be written client-direct to Firestore by the owner**, gated by Security Rules — callable Functions are required only for economy data and Trip lifecycle. Rules must still restrict client-writable values to non-privileged ones (e.g. a Task's `status` may be set to `todo`/`done` by the client, but `active` is server-set). *(Resolves `TASK-MIS-01`.)* | Client (owner) | §8 |

## Invariants

| ID | Invariant |
|---|---|
| TRUST-INV-01 | A user can never be driven to a negative balance. (See [economy-invariants.md](economy-invariants.md) `ECON-INV-01`.) |
| TRUST-INV-02 | A user can never be locked out of *all* play; the free undamageable **Starter vehicle** always permits a new Trip. (README §5 hard guardrail.) |

## The hard problem: partial observability (`TRUST-GAP-01`)

`TRUST-BR-09` exposes a genuine tension worth stating plainly, because it shapes Trip and the whole economy:

> **The server is declared authoritative over rewards, but the server cannot directly observe whether the
> user was focused.** While the user is "traveling," the app is in the foreground on their device; the
> server sees only the start call and the completion call. So "focused time" is fundamentally a
> **client-reported** quantity.

This means server authority is **bounded validation**, not **independent computation**:

- The server *can* enforce: completion no sooner than the claimed focus duration, focused-seconds ≤ wall-clock
  between server-stamped start and completion, one completion per Operation ID, sane per-trip maxima.
- The server *cannot* independently know: whether the user actually stared at their work or set the phone
  down with the app open and walked away.

**Implication:** a determined cheater who keeps the app foregrounded but does no real work can still earn.
That is **acceptable** — the product is a focus *aid* for cooperative users, not an anti-cheat fortress. The
defenses target *accidental* abuse and casual gaming (`min duration`, diminishing returns, idempotency), not
a motivated adversary.

> **Status — Accepted (2026-06-29).** Product accepts bounded validation only; residual foreground-idle gaming
> is out of scope for a cooperative focus aid. Revisit only if telemetry shows real farming.

## Misalignments

| ID | Tension | Resolution |
|---|---|---|
| TRUST-MIS-01 | README §10 "client may animate **optimistically**" vs §5 "accident roll happens **server-side** on return/completion." The client can animate a successful 40 km journey, then the server returns "accident — lose this trip's distance." | **Resolved** — provisional animation, **server-gated reveal**: the optimistic animation is explicitly provisional; on completion the client plays the server-decided outcome (success **or** accident) from the last confirmed state, with the balance view suppressed until the reveal. Never show banked rewards as final before the completion call returns. Owned in [feature/trip/BUSINESS.md](../../feature/trip/BUSINESS.md) `TRIP-BR-14` / `TRIP-FL-03`. |
| TRUST-MIS-02 | "Server authoritative over economy" vs "server can't see focus" (`TRUST-GAP-01`). | **Resolved** — authority reframed as **bounded validation** (above); residual gaming accepted as a signed-off product decision (`TRUST-GAP-01`, 2026-06-29). |
| TRUST-MIS-03 | README "daily consistency" streak vs `TRUST-BR-08` server-time authority — UTC midnight would split a Filipino user's evening session across two "days." | **Resolved** — a streak "day" is a **fixed PH-time (UTC+8) boundary computed server-side**, not UTC midnight and not the client clock. Recorded on `TRUST-BR-08`. |
