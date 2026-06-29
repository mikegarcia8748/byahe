# Distraction & Accident Model

> **Area code:** `DAM` · **Source:** README §5, §9, §10 · **Owned here; referenced by** Trip, Garage, Store.
>
> The canonical rules for how distraction accrues risk and how risk turns into accidents. Trip *drives* this
> model (it feeds distraction events and triggers the roll); Garage/Store *consume* its outputs (vehicle
> profiles, repair/lockout). Define it once here; features reference `DAM-*` rather than restating it.
>
> **All numeric constants below are `TUNABLE`** — placeholders pending playtesting (`DAM-GAP-04`). The *rules*
> are the contract; the *numbers* will move.

---

## 1. Scope

**In scope:** the Risk Meter, distraction tiers, the accident-probability formula, the severity model, vehicle
risk profiles, OS-interruption leniency, and the authority boundary for the roll.

**Not in scope:** what an accident *costs* the user (currency/progress/lockout consequences) — that's
[economy-invariants.md](economy-invariants.md) + [feature/trip/BUSINESS.md](../../feature/trip/BUSINESS.md);
how distraction is *detected* on each platform (Trip's concern); the streak/forgiveness ledger (Economy).

## 2. Key concepts

See [glossary.md](glossary.md): **Distraction**, **Distraction event**, **OS interruption**, **Risk Meter**,
**Accident roll**, **Severity**, **Accident chance**, **Focus multiplier**. This doc assumes those.

---

## 3. Business rules

| ID | Rule | Authority | Source |
|---|---|---|---|
| DAM-BR-01 | A **distraction** is the app leaving the foreground during an active Trip. This is the only reliably observable signal — we do **not** know which app was opened or read notification content. | Client (detect) / Server (judge) | §10 |
| DAM-BR-02 | While focused, `focus_multiplier = 1.0`; while distracted, `= 0` (the avatar pauses, no Distance accrues). | Shared | §4 |
| DAM-BR-03 | The **Risk Meter** is a session-scoped accumulator on `[0, 100]`, reconstructed **server-side from the distraction-event log**, never trusted as a client-supplied scalar. | Server | §5 + new |
| DAM-BR-04 | Risk accrues from **cumulative** distraction across the session, not a single lapse. (See tier table §4.) | Server | §5 |
| DAM-BR-05 | A distraction of **0–10 s adds no risk** (pause-before-punish), subject to the anti-abuse cap in `DAM-BR-11`. The client may show a gentle "still there?" prompt. | Shared | §5 |
| DAM-BR-06 | While focused, the Risk Meter **decays gradually** (`TUNABLE` ≈ −5 / focused-minute), floored at 0. It **never resets instantly** — a lapse lingers, so quick duck-out/duck-in cannot scrub it. | Server | §5 |
| DAM-BR-07 | **Accident chance** = `min( (riskMeter / 100) × vehicleRiskMult , 0.60 )`. The vehicle modifier is applied **inside** the cap, so **0.60 is an absolute ceiling** for every vehicle. *(Decision `DAM-MIS-01`.)* | Server | §5 |
| DAM-BR-08 | The **accident roll** (occurrence + severity) is evaluated **server-side at trip completion**, using the Risk Meter value at that moment. The client never holds the RNG, the formula, or the severity table as authority. | Server | §5, `TRUST-BR-04` |
| DAM-BR-09 | **Severity is gated by risk bands** (§5): the band sets the *maximum* possible severity; the vehicle's `severityMod` weights the outcome toward the worse end *within* the allowed band — it never exceeds the band ceiling. *(Decision `DAM-GAP-01`.)* | Server | §5 + new |
| DAM-BR-10 | **OS interruptions** that are system-attributable (incoming call, system permission dialog, low-battery dialog, forced backgrounding) are treated **leniently**: classified as a 0-risk event where detectable. Undetectable cases fall through to normal tiers. *(README "lean lenient.")* | Shared | §10 |
| DAM-BR-11 | **Anti-abuse on the grace band:** the 0–10 s no-risk grace (`DAM-BR-05`) is not unlimited. Beyond `TUNABLE` ≈ 4 grace events per rolling 5 min, further sub-10 s distractions begin accruing tier-2 risk, and **total distracted seconds** are tracked independently of per-event tiers. Closes the rapid duck-in/out loophole. | Server | new |
| DAM-BR-12 | The **Starter vehicle is damage-immune only**: an accident on it still costs the trip's Distance and a small fee, but never repair cost or lockout. *(Decision `DAM-GAP-02`; consequences live in Economy/Trip.)* | Server | §5 |

---

## 4. Risk Meter mechanics — distraction tiers (decision table)

Per **distraction event**, by its duration. Rates are `TUNABLE`.

| Tier | Event duration | Risk added | Client feedback |
|---|---|---|---|
| T0 | 0–10 s | **+0** (grace; capped by `DAM-BR-11`) | gentle "still there?" prompt |
| T1 | 10–60 s | **+ (seconds − 10) × k₁**, `k₁ ≈ 0.25/s` → up to ~+12 | avatar paused; subtle risk indicator |
| T2 | 60 s+ | T1 amount at 60 s, then **+ (seconds − 60) × k₂**, `k₂ ≈ 0.50/s` (faster) | avatar paused; clearer risk indicator |
| Return | back in foreground | none on return; **decay** begins per `DAM-BR-06` | avatar resumes; risk eases over focused minutes |

**Worked example (illustrative, `TUNABLE`):** focus 10 min → distracted 90 s → refocus 6 min → complete.
- Distraction adds T1(50 s)=≈+10 plus T2(30 s)=+15 → Risk ≈ 25.
- 6 focused minutes decay ≈ −30 → floored, Risk ≈ 0 at completion.
- → Accident chance ≈ 0. **The model rewards sustained re-focus** (a wellbeing-positive "redemption"), while a
  lapse *near* completion (little time to decay) stays dangerous.

---

## 5. Accident probability & severity

### Probability (`DAM-BR-07`)
```
accident_chance = min( (riskMeter / 100) × vehicleRiskMult , 0.60 )
```
- 60% hard ceiling for **all** vehicles (modifier is inside the `min`).
- A risky vehicle reaches the ceiling at a *lower* Risk Meter; it is never *more* likely than 60%.

### Severity bands (`DAM-BR-09`) — `TUNABLE` thresholds
The Risk Meter **at roll time** sets the worst severity possible; `vehicleSeverityMod` weights within the band.

| Risk Meter at roll | Possible severities | Notes |
|---|---|---|
| `< 30` | **Minor** only | a short lapse can never cause a major crash |
| `30 – 59` | Minor or **Moderate** | |
| `≥ 60` | Minor, Moderate, or **Major** | only deep, sustained distraction unlocks a Major |

> So the Risk Meter's top half is *not* wasted under the 0.60 probability cap (`DAM-MIS-02` concern): past 60 it
> stops raising *whether* you crash but keeps raising *how bad* it can be.

### Vehicle risk profiles (`DAM-BR-07`/`-09`)
Each catalog vehicle carries (`TUNABLE`):

| Field | Car | Motorcycle | Starter |
|---|---|---|---|
| `baseSpeed` (Distance/focused-min) | medium | high | low |
| `vehicleRiskMult` (→ probability) | `< 1` (≈0.8) | `> 1` (≈1.3) | low (≈0.7) |
| `vehicleSeverityMod` (→ within band) | toward milder | toward worse | n/a (damage-immune) |

Motorcycle danger therefore expresses through **two** channels — hits the 0.60 cap sooner *and* skews severity
worse — without ever breaking the cap. The "fast but risky vs. slow but stable" tradeoff is real, not cosmetic.

---

## 6. Edge cases

| ID | Scenario | Handling |
|---|---|---|
| DAM-EC-01 | **Screen off, app still foreground** (phone in pocket) — looks like focus. | Where the platform exposes prolonged screen-off, treat as a distraction-equivalent; otherwise accept as residual gaming per `TRUST-GAP-01`. Documented limitation. |
| DAM-EC-02 | **Exact tier boundaries** (10 s, 60 s). | Boundaries are inclusive-low / exclusive-high: 10.0 s → T1, 60.0 s → T2. Pin in `DAM-AC-02`. |
| DAM-EC-03 | **Many sub-10 s blips** to stay in the grace band. | `DAM-BR-11` caps grace events and tracks cumulative distracted seconds → escalates. |
| DAM-EC-04 | **System dialog steals foreground at trip start** (e.g. a permission prompt). | Classified OS-interruption (`DAM-BR-10`), 0 risk. |
| DAM-EC-05 | **Distraction in progress when the timer ends.** | Completion defers to next foreground; the roll (`DAM-BR-08`) sees the full distracted duration. (Trip `TRIP-EC-03`.) |
| DAM-EC-06 | **Risk decays to 0 before completion** after a serious lapse. | Intended: sustained re-focus is redemption. Short trips give less decay headroom, so late lapses still bite. |

## 7. Negative scenarios

| ID | Scenario | Handling |
|---|---|---|
| DAM-NS-01 | **Client sends a forged Risk Meter scalar.** | Ignored — server recomputes risk from the event log (`DAM-BR-03`). |
| DAM-NS-02 | **Client omits distraction events** to dodge risk. | Server can't independently see distraction; bounded by `TRUST-BR-09` (focused+distracted ≤ wall-clock) and accepted under `TRUST-GAP-01`. |
| DAM-NS-03 | **Force-quit during a distraction to dodge the roll.** | Roll is at completion only; never completing forfeits the reward and orphans the trip (`TRIP-EC-01`). No net gain. |
| DAM-NS-04 | **Clock manipulation** to fake decay / durations. | Server time authoritative (`TRUST-BR-08`); decay is computed from server-stamped intervals. |

## 8. Risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| DAM-RK-01 | False-positive distractions from OS interruptions feel unfair → frustration/churn. | High | `DAM-BR-10` leniency; accident forgiveness; encouraging tone (§9). |
| DAM-RK-02 | Platform can't detect target app / screen-off reliably → measurement gaps + gaming. | Med | Accept per `TRUST-GAP-01`; design around "left foreground," not surveillance. |
| DAM-RK-03 | Loss-on-probability feels gambling-adjacent → anxiety/compulsion. | High | 0.60 cap, "never lock out," recovery-not-prevention, forgiveness, encouraging copy (README §9). |
| DAM-RK-04 | Mis-tuned constants (too punishing or trivial) → churn or no stakes. | High | Every constant `TUNABLE`; playtest gate before launch (`DAM-GAP-04`). |

## 9. Gaps & open questions

| ID | Gap | Current default |
|---|---|---|
| DAM-GAP-03 | **Roll-on-return** (a mid-trip crash that ends travel early, more dramatic) vs the chosen **single roll at completion**. | Single completion roll (simpler, server-gated reveal). Revisit post-MVP for drama. |
| DAM-GAP-04 | **All tunable constants** — tier rates `k₁/k₂`, decay rate, band thresholds, `vehicleRiskMult`/`severityMod`. | Placeholders above; resolve by playtest. |
| DAM-GAP-05 | **Grace-band anti-abuse thresholds** (`DAM-BR-11`: events/window, cumulative cutoff). | Placeholder ≈4 / 5 min; tune. |

## 10. Acceptance criteria (Given/When/Then)

```
DAM-AC-01  Hard probability ceiling
  Given any vehicle with any vehicleRiskMult
  And a Risk Meter of 100 at roll time
  When the server computes accident_chance
  Then accident_chance is at most 0.60

DAM-AC-02  Grace band adds no risk
  Given an active trip with Risk Meter R
  When a single distraction of 9 seconds occurs (within grace allowance)
  Then the Risk Meter remains R

DAM-AC-03  Tier boundary is exclusive-high
  Given an active trip
  When a distraction lasts exactly 60 seconds
  Then it is scored under tier T2 (60 s+), not T1

DAM-AC-04  Severity is band-gated
  Given a Risk Meter of 25 at roll time
  When an accident occurs
  Then its severity is Minor (never Moderate or Major), regardless of vehicleSeverityMod

DAM-AC-05  Decay rewards sustained refocus, not duck-in/out
  Given a distraction raised the Risk Meter to 25
  When the user then stays focused for 6 minutes
  Then the Risk Meter has decayed toward 0 (TUNABLE rate)
  But Given four 8-second distractions within one minute
  Then the grace cap (DAM-BR-11) is exceeded and further blips accrue tier-2 risk

DAM-AC-06  Risk Meter is server-recomputed
  Given the client submits a completion payload asserting riskMeter = 0
  But the distraction-event log implies riskMeter = 55
  When the server evaluates the roll
  Then it uses 55, not the client-asserted 0

DAM-AC-07  Starter is damage-immune only
  Given the user is on the Starter vehicle
  When a Major-severity accident is rolled
  Then no repair cost and no lockout are applied
  But the trip's Distance is still lost and a small fee may apply
```
