# Trip — Business Specification

> **Area code:** `TRIP` · **Module:** `:feature:trip` · **Status:** pilot (first full spec) · **Source:** README §4, §5, §10
>
> The core game loop. Orchestrates one focus session from setup to a server-confirmed outcome. This is the
> module the whole product lives or dies on, so it carries the heaviest rule set.
>
> **Depends on cross-cutting docs:** [trust-boundary.md](../../docs/business/trust-boundary.md) (`TRUST-*`),
> [distraction-accident-model.md](../../docs/business/distraction-accident-model.md) (`DAM-*`),
> [economy-invariants.md](../../docs/business/economy-invariants.md) (`ECON-*`),
> [glossary.md](../../docs/business/glossary.md). Trip *drives* `DAM`/`ECON`; it does not redefine them.

---

## 1. Purpose & Scope

**Owns:** the Trip lifecycle — setup (task/duration/origin/destination/vehicle), the active timer + distance
accrual, distraction *detection* and event logging, the optimistic travel animation, the completion call, and
reconciling the client to the server's outcome.

**Not in scope (owned elsewhere):**
- Accident *probability/severity* → `DAM`. Accident *cost* and currency mechanics → `ECON`.
- Task CRUD → `:feature:tasks`. Vehicle catalog/customization/repair UI → `:feature:garage`.
- Currency wallet, ads, IAP → `:feature:store`. Streak *counting* + forgiveness ledger → `:feature:profile`.
- Map authoring / landmark catalog → map design (see `TRIP-GAP-04`).

## 2. Key Concepts

See [glossary.md](../../docs/business/glossary.md): **Trip**, **Focused/Distracted time**, **Distance**,
**Tick**, **Risk Meter**, **Accident roll**, **Optimistic UI**, **Operation ID**, **Orphaned Trip**,
**Starter vehicle**, **Landmark**, **Map progress**.

## 3. Actors & Triggers

| Actor | Triggers |
|---|---|
| **User** | selects task/duration/origin/destination/vehicle; starts; (optionally) abandons; returns to foreground |
| **Focus timer (client)** | elapses → completion condition |
| **OS lifecycle (client)** | foreground/background events → distraction events |
| **`startTrip` function (server)** | creates the active Trip, stamps server start time, fixes the Operation ID |
| **`completeTrip` function (server)** | computes Distance/reward, runs the roll (`DAM-BR-08`), applies costs, unlocks landmark, updates streak |
| **Server sweep (server, scheduled)** | closes orphaned active Trips past the grace window |

---

## 4. Business Rules

| ID | Rule | Authority | Source |
|---|---|---|---|
| TRIP-BR-01 | One Trip maps to exactly one Task. | Shared | §2 |
| TRIP-BR-02 | An account has **at most one ACTIVE Trip** at a time, enforced server-side at `startTrip`. | Server | `TRIP-GAP-02` |
| TRIP-BR-03 | Attempting to start while an ACTIVE Trip exists **resumes** the existing Trip. The user may *explicitly* abandon it (a deliberate, no-reward/no-penalty close) to start a new one. | Server | `TRIP-GAP-02` |
| TRIP-BR-04 | The selected **vehicle is locked for the Trip's duration** — no switching mid-Trip. | Shared | §4 |
| TRIP-BR-05 | A damaged/unusable vehicle **cannot be selected** at setup; the UI offers repair or the Starter. | Shared | `TRIP-EC-08` |
| TRIP-BR-06 | **Routes are duration-sized:** destination distance = chosen duration × vehicle `baseSpeed`, so a fully focused session ≈ arrival. | Shared | `TRIP-GAP-01` |
| TRIP-BR-07 | **Distance accrues only while focused** (`focus_multiplier = 1.0` focused, `0` distracted — `DAM-BR-02`). | Shared | §4 |
| TRIP-BR-08 | A Trip ends on whichever comes **first**: reaching the destination (**early arrival**) or the timer elapsing. Early arrival stops and banks (remaining timer is *not* bonus by default — `TRIP-GAP-06`). | Shared | §4 |
| TRIP-BR-09 | **Undershoot** (timer elapses before arrival) carries the focused Distance as **partial map progress** toward that destination, accumulating across Trips. | Server | `TRIP-GAP-01` |
| TRIP-BR-10 | **Fare is earned from focused time** (the `ECON` earning model) on *any* completion — arrival or timer. The **landmark unlock + map reveal requires actually reaching the destination.** Undershoot earns Fare + partial progress but no landmark unlock. | Server | §4 + new |
| TRIP-BR-11 | Completion is **server-authoritative** via `completeTrip`; the client never finalizes reward, accident, costs, landmark, or streak. | Server | §10, `TRUST-BR-01` |
| TRIP-BR-12 | `startTrip` **stamps the server start time**; it is the basis for wall-clock bounding of client-reported focus (`TRUST-BR-09`). | Server | new |
| TRIP-BR-13 | The **Operation ID is generated and persisted at `startTrip`**, before any completion attempt, and bound to the canonical completion request — so a retry after process death reuses it and cannot double-credit. | Server + Client | `TRIP-NS-03` |
| TRIP-BR-14 | **Server-gated reveal:** the client may animate travel optimistically, but the *outcome and balance* resolve only when `completeTrip` returns. The currency Firestore listener is **suppressed from view** until the reveal plays. | Shared | `TRUST-MIS-01` |
| TRIP-BR-15 | If the timer elapses **while distracted**, completion **defers to the next foreground**, and the deferred completion includes the full distracted duration. | Shared | `TRIP-EC-03` |
| TRIP-BR-16 | An ACTIVE Trip with no completion within **(duration + grace window)** is **swept server-side to ABANDONED** — no reward, no penalty. | Server | `TRIP-EC-01` |
| TRIP-BR-17 | Reopening within the **resume window** restores the Trip to its last server-known state and continues it. | Shared | `TRIP-EC-01`, `TRIP-GAP-02` |
| TRIP-BR-18 | `focusedSeconds` / `distractedSeconds` and the **distraction-event log** are client-reported but **server-bounded** (`TRUST-BR-09`); the server recomputes the Risk Meter from the event log (`DAM-BR-03`), never trusting a client scalar. | Server | `DAM`, `TRUST` |
| TRIP-BR-19 | Offline completion is **queued locally with its Operation ID and replayed on reconnect**; idempotency makes replay safe. UI shows "saved, syncing." | Client + Server | `TRIP-NS-01` |

---

## 5. Flows

**`TRIP-FL-01` Happy path.** Setup → `startTrip` (server start, opId) → focus to arrival/timer → `completeTrip`
→ server: Fare + (if arrived) landmark unlock + map reveal + streak bonus → success reveal animation.

**`TRIP-FL-02` Distracted-but-recovered.** Active → app backgrounded (distraction event logged) → avatar
paused, risk accrues (`DAM`) → return → risk decays over focused minutes → complete → low/zero accident chance.

**`TRIP-FL-03` Accident on completion (the reconciliation moment).** Active travel animates optimistically →
`completeTrip` returns an **accident** result → client does **not** keep the optimistic success; it plays the
server-decided accident outcome from the last confirmed state, applies costs (or forgiveness, `ECON-BR-09`),
and surfaces recovery options. *This is `TRUST-MIS-01` made concrete; never bank rewards before the call returns.*

**`TRIP-FL-04` Early arrival.** Focused Distance reaches route length before the timer → Trip ends, banks,
landmark unlocks (`TRIP-BR-08`). Remaining timer discarded by default (`TRIP-GAP-06`).

**`TRIP-FL-05` Undershoot.** Timer elapses before arrival → `completeTrip` → Fare from focused time + **partial
map progress**, no landmark unlock (`TRIP-BR-09/-10`).

**`TRIP-FL-06` Offline completion.** Completion condition hit with no network → persist completion intent (opId)
→ "saved, syncing" → on reconnect, replay → server confirms once (`TRIP-BR-19`, `ECON-INV-04`).

**`TRIP-FL-07` Orphaned Trip.** App killed mid-Trip. Reopen **within** resume window → resume (`TRIP-BR-17`).
Not reopened within (duration + grace) → server sweep → ABANDONED (`TRIP-BR-16`).

**`TRIP-FL-08` Resume on new-start attempt.** User taps "start" while an ACTIVE Trip exists → app returns them
to the in-progress Trip (`TRIP-BR-03`); to start fresh they must explicitly abandon the active one.

---

## 6. States & Transitions

States: `SETUP`, `ACTIVE`, `DISTRACTED`, `COMPLETING`, `COMPLETED`, `ACCIDENT`, `ABANDONED`.

| From | Event | Guard | To |
|---|---|---|---|
| SETUP | confirm start | valid task/duration/vehicle; no other ACTIVE trip (else resume per `TRIP-BR-03`) | ACTIVE |
| ACTIVE | app backgrounded | — | DISTRACTED |
| DISTRACTED | app foregrounded | timer **not** elapsed | ACTIVE (log event; begin decay) |
| ACTIVE | reach destination | Distance ≥ route length | COMPLETING (early arrival) |
| ACTIVE | timer elapses | in foreground | COMPLETING |
| DISTRACTED | timer elapses while away | — | DISTRACTED → (on next foreground) COMPLETING (`TRIP-BR-15`) |
| COMPLETING | `completeTrip` returns success | — | COMPLETED |
| COMPLETING | `completeTrip` returns accident | — | ACCIDENT |
| COMPLETING | offline / call fails | — | COMPLETING (queued; replays — `TRIP-BR-19`) |
| ACTIVE/DISTRACTED | app killed, reopened ≤ resume window | — | ACTIVE (resume — `TRIP-BR-17`) |
| ACTIVE/DISTRACTED | no completion within duration+grace | — | ABANDONED (server sweep — `TRIP-BR-16`) |
| ACTIVE/DISTRACTED/SETUP | user explicitly abandons | — | ABANDONED (no reward/penalty) |

`COMPLETED`, `ACCIDENT`, `ABANDONED` are terminal for that Trip.

---

## 7. Contracts (business-level)

**Reads (Firestore listeners, read-only — `TRUST-BR-03`):** active trip state, currency wallet (view-gated per
`TRIP-BR-14`), vehicle catalog + garage condition, landmark/map-progress catalog, task list.

**Writes (callable functions only — `TRUST-BR-02`):**
- `startTrip(taskId, vehicleId, duration, origin, destination)` → creates ACTIVE trip, server start time, opId.
- `completeTrip(tripId, opId, focusedSeconds, distractedSeconds, distractionEvents)` → computes everything; idempotent.
- `abandonTrip(tripId)` → explicit forfeit.

**Outcome ownership:** server owns reward, accident, costs, landmark unlock, map progress, streak. Client owns
timer display, distraction detection, optimistic Distance animation, and reconciliation rendering.

## 8. Economy Effects

All money/loss behavior defers to [economy-invariants.md](../../docs/business/economy-invariants.md): earning
model (ramp-up/taper) `ECON-BR-01..03`; accident cost mapping `ECON-BR-06..09`; never-negative/idempotent
`ECON-INV-01/-04`; forgiveness auto-apply `ECON-BR-09`; starter cost cap `ECON-BR-08`. Trip supplies the
*inputs* (focused time, distraction log, vehicle, arrival flag) and renders the *result*; it computes no money.

---

## 9. Edge Cases

| ID | Scenario | Handling |
|---|---|---|
| TRIP-EC-01 | App killed mid-Trip, never reopened. | Resume window then server sweep (`TRIP-BR-16/-17`). |
| TRIP-EC-02 | Screen off but app foreground. | `DAM-EC-01` — distraction-equivalent where detectable, else accepted (`TRUST-GAP-01`). |
| TRIP-EC-03 | Timer elapses while distracted. | Completion defers to next foreground (`TRIP-BR-15`). |
| TRIP-EC-04 | Early arrival (Distance ≥ route before timer). | Trip ends & banks (`TRIP-BR-08`). |
| TRIP-EC-05 | System dialog steals foreground at start. | `DAM-EC-04` — classified OS-interruption, 0 risk. |
| TRIP-EC-06 | Clock change / DST / crossing timezones mid-Trip. | Server time authoritative (`TRUST-BR-08`); streak day is fixed PH-time (`TRUST-MIS-03`). |
| TRIP-EC-07 | Currency listener pushes a balance change mid-animation. | View-gated until the reveal plays (`TRIP-BR-14`). |
| TRIP-EC-08 | Damaged vehicle selected at setup. | Blocked; offer repair or Starter (`TRIP-BR-05`). |
| TRIP-EC-09 | Location permission denied at origin selection. | Fall back to saved Google address or manual entry (README §4); never block starting. |
| TRIP-EC-10 | No unexplored landmark left in the current region. | Offer a revisit/return route or the next region's gateway; never a dead-end setup (`TRIP-GAP-04`). |
| TRIP-EC-11 | Phone reboot mid-Trip. | Same as app-kill → resume/sweep (`TRIP-EC-01`). |
| TRIP-EC-12 | OS memory-pressure kill or app update mid-Trip. | Same as app-kill; resume restores last server-known state (`TRIP-BR-17`). |
| TRIP-EC-13 | Resume attempted on Device B while Device A still shows the Trip active. | Single ACTIVE trip is server-truth (`TRIP-BR-02`); both devices reconcile to it via listener; only one completion wins (`TRIP-NS-06`). |
| TRIP-EC-14 | Very short chosen duration → tiny route. | Allowed; ramp-up (`ECON-BR-02`) keeps the reward small so this isn't a farm. |

## 10. Negative Scenarios

| ID | Scenario | Handling |
|---|---|---|
| TRIP-NS-01 | Offline at completion. | Queue & replay (`TRIP-BR-19`). |
| TRIP-NS-02 | `completeTrip` times out / response lost → client retries. | Same opId → server dedupes (`ECON-INV-04`). |
| TRIP-NS-03 | Operation ID lost to process death before persistence. | opId persisted at `startTrip` (`TRIP-BR-13`), not at completion. |
| TRIP-NS-04 | Client inflates `focusedSeconds` to farm Fare. | Server bounds to wall-clock(start..end), chosen duration, per-trip max (`TRUST-BR-09`); residual accepted (`TRUST-GAP-01`). |
| TRIP-NS-05 | Force-quit / airplane mode to dodge the accident roll. | Roll only at completion; never completing forfeits reward + orphans the Trip (`TRIP-EC-01`). No net gain. |
| TRIP-NS-06 | Two devices both call `completeTrip` for the same Trip. | One ACTIVE trip + idempotent opId → exactly one completion applies. |
| TRIP-NS-07 | Client claims arrival without enough focused Distance. | Server recomputes Distance from server-bounded focused time × vehicle speed; arrival/landmark granted only if it genuinely reaches route length. |

## 11. Risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| TRIP-RK-01 | Accident losses feel unfair → churn. | High | `DAM-RK-03` set: cap, forgiveness, starter floor, encouraging tone. |
| TRIP-RK-02 | Optimistic→accident reconciliation feels like the game "took back" a win. | Med | Server-gated reveal (`TRIP-BR-14`): never present success as final pre-call; frame accident as its own beat. |
| TRIP-RK-03 | Client focus-time inflation exploit. | Med | Bounded validation (`TRUST-BR-09`); accepted residual (`TRUST-GAP-01`). |
| TRIP-RK-04 | iOS background suspension makes distracted-time/timer measurement coarse. | High | Measure via server-bounded foreground/background timestamps, not a background timer; lean lenient (`DAM-BR-10`). |
| TRIP-RK-05 | Resume/sweep state machine is bug-prone (the hardest logic here). | High | Server is single source of trip truth; exhaustive state-table tests; idempotent completion. |
| TRIP-RK-06 | Location-permission friction at setup → drop-off. | Med | Graceful fallbacks (`TRIP-EC-09`); location is optional, not a gate. |

## 12. Gaps & Open Questions

| ID | Gap | Current default |
|---|---|---|
| TRIP-GAP-01 | Route length ↔ duration. | **Resolved:** duration-sized routes (`TRIP-BR-06`). |
| TRIP-GAP-02 | Concurrency / multiple active trips. | **Resolved:** one active, second start resumes (`TRIP-BR-02/-03`). |
| TRIP-GAP-03 | **Voluntary pause** (e.g. bathroom break) without penalty? | None in MVP — backgrounding is a distraction; brief breaks fall in the grace band (`DAM-BR-05`). Revisit if users demand it (abuse risk). |
| TRIP-GAP-04 | **Destination selection** algorithm (which next landmark; dead-end regions). | Default: nearest unexplored or user choice; finalize with map design. |
| TRIP-GAP-05 | **Resume window + grace duration** values. | `TUNABLE` — placeholder, set with playtest. |
| TRIP-GAP-06 | Does leftover timer after **early arrival** become a bonus? | Default: no — stop & bank. Could become a "bank extra Fare" beat later. |
| TRIP-GAP-07 | **Partial map-progress accumulation** model for undershoot. | Carry focused Distance toward the landmark; exact persistence model needs map design. |

## 13. Misalignments & Tensions

| ID | Tension | Resolution |
|---|---|---|
| TRIP-MIS-01 | Optimistic animation (§4/§10) vs server-side roll (§5). | Server-gated reveal (`TRIP-BR-14`, `TRIP-FL-03`); see `TRUST-MIS-01`. |
| TRIP-MIS-02 | "Avatar moves only while focused" — but while distracted the app is backgrounded and not rendering, so the *visible* pause is really shown retroactively on return. | Accept: the pause is a return-time confirmation, not a live frame. The behavior being trained (don't leave) is still reinforced on return. |
| TRIP-MIS-03 | Server-authoritative reward vs server cannot observe focus. | Bounded validation (`TRUST-BR-09`, `TRUST-GAP-01`). |
| TRIP-MIS-04 | README "roll on return / on completion" vs the chosen single completion roll. | Single completion roll (`DAM-BR-08`); roll-on-return deferred (`DAM-GAP-03`). |

## 14. Wellbeing Guardrails (README §9 applied to Trip)

- **Encouraging, not shaming:** distraction prompt is "still there?", reconciliation frames an accident as a
  setback with recovery, never "you failed."
- **Never lock out:** Starter is always selectable (`ECON-INV-07`); a damaged garage never blocks a Trip.
- **No anxiety roll during distraction:** the roll is deferred to completion (`DAM-BR-08`) — no "will I crash?" screen while away.
- **No pay-to-avoid-risk:** setup offers no purchase that removes Trip risk (`ECON-INV-05`).
- **Marathon care:** reward taper past ~90 min (`ECON-BR-03`); avatar unaffected.

## 15. Acceptance Criteria (Given/When/Then)

```
TRIP-AC-01  One active trip; start resumes
  Given the user already has an ACTIVE trip
  When they attempt to start a new trip
  Then they are returned to the existing trip (not given a second active trip)

TRIP-AC-02  Duration-sized route
  Given a 25-minute duration and a vehicle with baseSpeed S
  When the route is created
  Then the destination distance equals 25 × S

TRIP-AC-03  Early arrival banks and unlocks
  Given focused Distance reaches the route length before the timer ends
  When the trip completes
  Then it ends immediately, Fare is granted, and the destination landmark unlocks

TRIP-AC-04  Undershoot earns Fare + partial progress, no landmark
  Given the timer elapses before the destination is reached
  When the trip completes
  Then Fare is granted for the focused time
  And partial map progress is recorded toward the destination
  But the landmark does NOT unlock

TRIP-AC-05  Server-gated reveal on accident
  Given the client optimistically animated a successful arrival
  When completeTrip returns an accident result
  Then the client renders the accident outcome (not the optimistic success)
  And the visible balance updates only after the reveal plays

TRIP-AC-06  Offline completion replays exactly once
  Given the trip completes while offline with Operation ID "op-7"
  When connectivity returns and "op-7" is replayed
  Then the server applies the completion exactly once

TRIP-AC-07  Timer-while-distracted defers completion
  Given the timer elapses while the app is backgrounded
  When the user next foregrounds the app
  Then completion runs then, including the full distracted duration

TRIP-AC-08  Orphaned trip is swept, not penalized
  Given an ACTIVE trip with no completion within duration + grace
  When the server sweep runs
  Then the trip becomes ABANDONED with no reward and no penalty

TRIP-AC-09  Damaged vehicle cannot start a trip
  Given the user's selected vehicle is unusable (needs repair)
  When they open setup
  Then that vehicle cannot be chosen, and repair or the Starter is offered

TRIP-AC-10  Inflated focus time is bounded
  Given the wall-clock between server start and completion is 600 s
  When the client reports focusedSeconds = 5000
  Then the server clamps focusedSeconds to at most 600 (minus distracted time)
```
