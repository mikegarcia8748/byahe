# Risk Register

> Rolled-up index of every `*-RK-*` (risk) and every **open** `*-GAP-*` (undecided / needs-tuning) across the
> business specs, plus the accepted trade-offs and the resolved decisions. The per-doc entries remain the source
> of truth; this is the cross-cutting view for prioritization.
>
> **Scope:** `TRUST`, `DAM`, `ECON`, `TRIP`, `TASK`. Update as feature specs are added.
> **Last updated:** 2026-06-29.

---

## 1. Priority view

**P1 — address before launch** (high severity, core to fairness/economy/trust):

- `DAM-RK-03` / `ECON-RK-02` / `TRIP-RK-01` — **loss feels punishing / gambling-adjacent** (one theme, three docs).
- `DAM-RK-04` / `ECON-RK-01` — **mis-tuned constants** break the economy or the stakes (one theme).
- `DAM-RK-01` — OS-interruption false positives feel unfair.
- `TRIP-RK-04` — iOS background suspension → coarse focus/distraction measurement.
- `TRIP-RK-05` — resume/sweep state machine is the most bug-prone logic in the app.
- `TASK-RK-02` — the client-direct-write precedent must not leak to economy data.

**P2 — manage / monitor:**

- `DAM-RK-02` platform detection limits · `ECON-RK-03` pay-to-win drift · `ECON-RK-04` invisible wellbeing taper ·
  `ECON-RK-05` ad/IAP fraud · `TRIP-RK-02` reconciliation UX · `TRIP-RK-03` focus-time inflation ·
  `TRIP-RK-06` location-permission friction.

---

## 2. Risk table

| ID | Risk | Sev | Theme | Primary mitigation |
|---|---|---|---|---|
| DAM-RK-03 | Probabilistic loss feels gambling-adjacent → anxiety/compulsion | High | **Wellbeing** | 0.60 cap; "never lock out"; recovery-not-prevention; forgiveness; encouraging copy |
| ECON-RK-02 | Currency loss on accident feels punishing | High | **Wellbeing** | Clamp to non-negative; cap %; forgiveness; recovery options |
| TRIP-RK-01 | Accident losses feel unfair → churn | High | **Wellbeing** | Cap + forgiveness + starter floor + non-shaming reconciliation |
| DAM-RK-04 | Mis-tuned tier/decay/band/vehicle constants → too punishing or trivial | High | **Tuning** | All constants `TUNABLE`; playtest gate (`DAM-GAP-04`) |
| ECON-RK-01 | Mis-tuned rates → inflation or grindy economy | High | **Tuning** | Source/sink modelling; `TUNABLE`; playtest (`ECON-GAP-04`) |
| DAM-RK-01 | OS-interruption false positives feel unfair | High | **Platform** | `DAM-BR-10` leniency; forgiveness |
| TRIP-RK-04 | iOS suspension → coarse distracted-time/timer measurement | High | **Platform** | Server-bounded fg/bg timestamps, not a bg timer; lean lenient |
| TRIP-RK-05 | Resume/sweep state machine is bug-prone | High | **Correctness** | Server is single trip-truth; exhaustive state-table tests; idempotent completion |
| DAM-RK-02 | Can't detect target app / screen-off reliably | Med | **Platform** | Accept (`TRUST-GAP-01`); design around "left foreground" |
| ECON-RK-03 | Premium advantages drift into pay-to-win | Med | **Integrity** | `ECON-INV-05/-06`: premium = cosmetic/convenience/content only |
| ECON-RK-04 | Reward-only taper makes the ~90-min nudge invisible | Med | **Wellbeing** | Flagged (`ECON-BR-03`); revisit rest nudge if telemetry shows marathons |
| ECON-RK-05 | Ad-reward / IAP fraud drains or inflates economy | Med | **Security** | Server validation + idempotency + daily caps |
| TRIP-RK-02 | Optimistic→accident reconciliation feels like a taken-back win | Med | **UX** | Server-gated reveal (`TRIP-BR-14`); never present success as final pre-call |
| TRIP-RK-03 | Client focus-time inflation exploit | Med | **Security** | Bounded validation (`TRUST-BR-09`); accepted residual (`TRUST-GAP-01`) |
| TRIP-RK-06 | Location-permission friction at setup → drop-off | Med | **UX** | Graceful fallbacks; location optional, not a gate (`TRIP-EC-09`) |
| TASK-RK-02 | Client-direct-write precedent misapplied to economy data later | High | **Integrity** | `TRUST-BR-10` scopes client-direct writes to non-economy data only |
| TASK-RK-01 | Client-direct writes move validation into Security Rules → malformed data | Med | **Correctness** | Schema validation in Rules; small task shape (`TASK-GAP-06`) |
| TASK-RK-03 | Multi-device last-write-wins → minor edit loss | Low | **UX** | Acceptable for non-economy data; field-level merge where trivial |
| TASK-RK-04 | Unrewarded todo completion feels flat | Low | **UX** | Intentional (`TASK-BR-09`); optional streak/achievement nod later |

> **Two clusters dominate.** Most P1 risk reduces to (a) *keeping loss from feeling like gambling* and (b)
> *getting the numbers right*. Both are mitigated by the same levers — caps, forgiveness, never-lock-out, and a
> disciplined `TUNABLE` → playtest loop — so the pre-launch tuning pass is the single highest-leverage activity.

---

## 3. Open gaps (need a decision or tuning)

| ID | Gap | Type | Current default |
|---|---|---|---|
| DAM-GAP-03 | Roll-on-return (mid-trip crash) vs single completion roll | Decision | Completion roll; revisit post-MVP |
| DAM-GAP-04 | All `DAM` tunable constants | Tuning | Placeholders; playtest |
| DAM-GAP-05 | Grace-band anti-abuse thresholds | Tuning | ≈4 / 5 min; tune |
| ECON-GAP-04 | All `ECON` tunable constants | Tuning | Placeholders; economy model + playtest |
| ECON-GAP-05 | Hard session reward ceiling beyond the taper? | Decision | None for now |
| ECON-GAP-06 | Fuel-system scope | Decision (deferred) | Deferred (README §11); keep non-punitive |
| TRIP-GAP-03 | Voluntary pause without penalty? | Decision | None in MVP; brief breaks use grace band |
| TRIP-GAP-04 | Destination-selection algorithm / dead-end regions | Decision | Nearest-unexplored or user choice; finalize with map design |
| TRIP-GAP-05 | Resume-window + grace durations | Tuning | `TUNABLE`; playtest |
| TRIP-GAP-06 | Leftover timer after early arrival = bonus? | Decision | No — stop & bank |
| TRIP-GAP-07 | Partial map-progress accumulation model | Decision | Carry distance toward landmark; needs map design |
| TASK-GAP-01/-02/-03 | Recurring tasks · subtasks · priority/tags | Decision (deferred) | Deferred post-MVP; default order by created/updated |
| TASK-GAP-06 | Security-Rules schema validation specifics | Tuning | Define before Rules are written |

> Most open gaps are **tuning** (resolve in the playtest pass) or **blocked on map design** (`TRIP-GAP-04/-07`,
> a likely next spec). Few are open *product* questions, because Batches 1–4 closed the consequential ones.

## 4. Accepted trade-offs

| ID | Trade-off | Status |
|---|---|---|
| TRUST-GAP-01 | Server can't fully verify focus; residual foreground-idle gaming is possible. Defenses target casual abuse, not motivated adversaries. | **Accepted 2026-06-29** — bounded validation only; revisit if telemetry shows farming |

## 5. Resolved decisions (Batches 1–4)

For traceability; details in each doc. Resolved gaps are removed from §3.

| ID | Decision |
|---|---|
| DAM-MIS-01 | Vehicle modifier **inside** the probability cap (60% absolute ceiling) — `DAM-BR-07` |
| DAM-GAP-01 | Severity by **risk-band thresholds** × vehicle severity mod — `DAM-BR-09` |
| DAM-GAP-02 | Starter vehicle **damage-immune only** — `DAM-BR-12` / `ECON-BR-08` |
| ECON-GAP-01 | Anti-farm via **reward ramp-up** (no cliff) — `ECON-BR-02` |
| ECON-GAP-02 | **Reward-only taper** past ~90 min — `ECON-BR-03` |
| ECON-GAP-03 | **Auto-apply + surfaced** accident forgiveness — `ECON-BR-09` |
| TRIP-GAP-01 | **Duration-sized routes** — `TRIP-BR-06` |
| TRIP-GAP-02 | **One active trip; second start resumes** — `TRIP-BR-02/-03` |
| TRIP-EC-01 | **Resume window + server sweep** for orphaned trips — `TRIP-BR-16/-17` |
| TRIP-NS-01 | **Queue & replay** offline completion — `TRIP-BR-19` |
| TRUST-MIS-01 | **Provisional animation, server-gated reveal** — `TRIP-BR-14` |
| TRUST-MIS-03 | Streak day = **fixed PH-time (UTC+8), server-side** — `TRUST-BR-08` |
| TASK-① | **Many trips per task; manual completion** — `TASK-BR-02` |
| TASK-② | **Client-direct Firestore writes** for non-economy data — `TASK-BR-03` / `TRUST-BR-10` |
| TASK-③ | **`estimatedMinutes` pre-fills trip duration** — `TASK-BR-04` |
| TASK-④ | **Task locked during active trip** — `TASK-BR-05` |
| TASK-MIS-01 | README §0 "never writes DB" reconciled with §8 — non-economy data is owner-writable — `TRUST-BR-10` |
