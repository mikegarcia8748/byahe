# Economy Invariants

> **Area code:** `ECON` · **Source:** README §5–§7, §8, §10 · **Owned here; referenced by** Trip, Garage, Store.
>
> The rules that keep currency, rewards, and losses correct and safe across **every** feature. Where the
> [distraction-accident-model.md](distraction-accident-model.md) decides *whether/how bad* an accident is, this
> doc decides *what it costs* and how every currency change stays consistent. Features reference `ECON-*`
> rather than restating money rules.
>
> **Numeric constants are `TUNABLE`** (`ECON-GAP-04`) — the rules are the contract, the numbers will move.

---

## 1. Scope

**In scope:** the two currencies; the non-negotiable money invariants (never-negative, atomic, idempotent,
append-only ledger); the earning model (ramp-up, taper, streak); accident *cost* mapping; accident
forgiveness; spending sinks and insufficient-funds handling; concurrency/idempotency.

**Not in scope:** accident *probability/severity* (that's `DAM`); IAP/ads UX and receipt validation flow
(Store feature); the streak *counting* rules beyond their economy effect (Profile feature).

## 2. Key concepts

See [glossary.md](glossary.md): **Fare**, **Gems / Tokens**, **Ledger**, **Operation ID**, **Repair**,
**Repair-rush**, **Streak**, **Accident forgiveness**, **Diminishing returns**, **Starter vehicle**.

## 3. Currencies

| Currency | Kind | Earned by | Spent on |
|---|---|---|---|
| **Fare** | soft | completing Trips (the earning model, §5) | repairs, fuel, basic customization |
| **Gems / Tokens** | premium-ish | slow drip via streaks/achievements; **purchased** (IAP) | cosmetics, vehicle unlocks, repair-rush |

---

## 4. Invariants (must *always* hold)

| ID | Invariant | Authority |
|---|---|---|
| ECON-INV-01 | **Balance never goes negative.** A *voluntary* spend that would breach 0 is **blocked**; an *involuntary* loss (accident fee/%) is **clamped** to available balance. The two paths differ — see `ECON-BR-07`. | Server |
| ECON-INV-02 | Every currency change writes an **append-only ledger entry** (type, currency, amount, balanceAfter, sourceOpId, createdAt). Ledger entries are never mutated or deleted. | Server |
| ECON-INV-03 | Balance update + ledger append happen in **one atomic transaction** — never one without the other. | Server |
| ECON-INV-04 | Reward/grant/spend operations are **idempotent**, keyed by Operation ID; a retried request never double-applies. The opId is bound to its first canonical request (a mismatched replay is rejected). | Server |
| ECON-INV-05 | **No pay-to-avoid-risk.** Paying to *recover after* an accident (repair, repair-rush) is allowed; paying to make a Trip risk-free **before** the fact is not. | Server |
| ECON-INV-06 | Cosmetic purchases (paint, decals, accessories) have **no gameplay effect** — vanity only. | Shared |
| ECON-INV-07 | The free, undamageable **Starter vehicle** is always available, so the user can always start another Trip (the "never lock out" floor, with `TRUST-INV-02`). | Server |
| ECON-INV-08 | The **earning formula is authoritative on the server.** Any client-side figure is a display-only estimate (`TRUST-BR-01/-09`). | Server |

---

## 5. Earning model

### Formula (`ECON-BR-01`) — extends README §6
```
fare_earned = base_rate × reward_minutes × vehicle_efficiency × streak_bonus
```
- `reward_minutes` is **not** raw focused minutes; it is the integral of a per-minute weight curve that encodes
  ramp-up and taper:

```
minute_weight(t):                         t = focused minute index
  ramp   (0 .. RAMP_FULL≈5–10 min):   rises 0 → 1     ← anti-farm, no cliff  (ECON-GAP-01)
  normal (RAMP_FULL .. 90 min):       1.0
  taper  (> 90 min):                  falls 1 → TAPER_FLOOR   ← wellbeing      (ECON-GAP-02)
reward_minutes = Σ minute_weight(t)
```

| ID | Rule | Authority |
|---|---|---|
| ECON-BR-02 | **Ramp-up (anti-farm).** Reward scales smoothly from 0 toward full across the first `RAMP_FULL` focused minutes, so micro-sessions earn ~nothing and there is **no cliff**. *(Decision `ECON-GAP-01`.)* | Server |
| ECON-BR-03 | **Taper (wellbeing).** Past ~90 focused minutes the per-minute reward weight declines toward `TAPER_FLOOR`. **Reward only — the avatar/Distance is unaffected** (no confusing slowdown). *(Decision `ECON-GAP-02`.)* | Server |
| ECON-BR-04 | **Streak credit floor.** A Trip grants streak/`streak_bonus` credit only if its `reward_minutes` clears a minimum (`TUNABLE`), so micro-sessions cannot farm streaks either. | Server |
| ECON-BR-05 | `vehicle_efficiency` and `streak_bonus` are catalog/▸account values resolved server-side; the client never asserts them. | Server |

> **Wellbeing note (`ECON-RK-04`):** because the taper is reward-only and invisible to a user not watching their
> Fare, the ~90-min wellbeing intent rests entirely on it. A non-blocking rest nudge was considered and **not**
> adopted (Batch-2 decision); revisit if wellbeing telemetry shows long marathon sessions.

---

## 6. Accident cost mapping

The `DAM` model returns *severity*; this table defines the *cost* (README §5 outcomes), under `ECON-INV-01`.

| Severity | Progress | Currency | Vehicle |
|---|---|---|---|
| **Minor** | Lose this Trip's Distance; keep map progress | Small flat **fee** | Cosmetic scratch — no cost |
| **Moderate** | Lose Trip + **small %** of banked Fare | Medium **repair cost** | Unusable until repaired |
| **Major** | Lose Trip + **larger %** of banked Fare | High repair + **hospitalization fee** | Unusable for X hrs unless repaired/repair-rushed |

| ID | Rule | Authority |
|---|---|---|
| ECON-BR-06 | All accident costs are applied **server-side** in the completion transaction, after the roll (`DAM-BR-08`), with a ledger entry per charge. | Server |
| ECON-BR-07 | **Involuntary losses are clamped, not blocked.** A `%`-of-banked or fee charge that exceeds the balance reduces it to exactly 0 — never below (`ECON-INV-01`). (Contrast voluntary spend, which is *blocked*.) | Server |
| ECON-BR-08 | **Starter cost cap (`DAM-BR-12`).** On the Starter vehicle, accident cost is capped at *lose Distance + small fee* regardless of rolled severity: no repair cost, no lockout, no large `%` currency loss. | Server |
| ECON-BR-09 | **Forgiveness short-circuits cost.** If the user holds Accident forgiveness, it is **auto-applied before** any cost: the entire consequence of one accident is waived, one forgiveness is consumed, and the result screen surfaces it ("your streak saved you"). *(Decision `ECON-GAP-03`.)* | Server |

---

## 7. Spending sinks & insufficient funds

| ID | Rule | Authority |
|---|---|---|
| ECON-BR-10 | **Spending sinks:** repairs (loss recovery), fuel/maintenance (light, **non-punitive**, optional), cosmetic customization (no gameplay effect), vehicle unlocks (gated by **both** currency **and** map progress). | Shared |
| ECON-BR-11 | **Voluntary spend is blocked, not clamped:** if the user lacks funds, the purchase/repair is refused with recovery options (e.g. a rewarded ad, or use the Starter), never forcing a negative balance. | Server |
| ECON-BR-12 | **Repair-rush is pay-to-recover (allowed).** Shortening/skipping a repair or lockout timer via currency/Gems/ad is permitted; it never prevents a *future* accident (`ECON-INV-05`). | Server |
| ECON-BR-13 | Vehicle unlocks require the currency cost **and** the map-progress requirement to be met server-side (e.g. "reach Cebu to unlock the ferry"). | Server |

---

## 8. Edge cases

| ID | Scenario | Handling |
|---|---|---|
| ECON-EC-01 | **Accident `%` loss when balance is near 0.** | `ECON-BR-07` clamp → balance hits 0, ledger records the actual (clamped) amount, never a negative. |
| ECON-EC-02 | **Major accident while holding forgiveness.** | `ECON-BR-09` waives the whole consequence before costs; forgiveness −1; surfaced. |
| ECON-EC-03 | **Trip ends just under the ramp/streak floor.** | Earns the ramped (near-0) Fare per `ECON-BR-02`; grants **no** streak credit per `ECON-BR-04`. |
| ECON-EC-04 | **Marathon past 90 min.** | Keeps earning at the tapered weight; avatar unaffected; no hard stop (unless a `HARD_CEILING` is later set in `ECON-GAP-04`). |
| ECON-EC-05 | **Unlock affordable in currency but map requirement unmet.** | Refused with the reason surfaced (`ECON-BR-13`) — currency alone is insufficient. |

## 9. Negative scenarios

| ID | Scenario | Handling |
|---|---|---|
| ECON-NS-01 (was STORE-NS-01) | **Concurrent spend from two devices** races the balance below 0. | Each mutation runs in a transaction reading current balance; loser retries; `ECON-INV-01` holds atomically. |
| ECON-NS-02 (was STORE-NS-02) | **Idempotency key replayed with a *different* payload** (tampered retry). | opId is bound to its first canonical request (`ECON-INV-04`); mismatched replay is rejected, not applied. |
| ECON-NS-03 | **Client asserts a Fare amount / vehicle_efficiency / streak_bonus.** | Ignored; server computes from authoritative values (`ECON-INV-08`, `ECON-BR-05`). |
| ECON-NS-04 | **Rewarded-ad reward claimed twice / spoofed.** | Ad reward is granted via an idempotent server op tied to a verified ad callback, daily-capped; client claim alone is never trusted. |
| ECON-NS-05 | **IAP purchase claimed without payment.** | Server validates the store receipt before granting (`TRUST-BR-06`); unvalidated claims grant nothing. |

## 10. Risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| ECON-RK-01 | Mis-tuned rates → runaway inflation or a grindy, joyless economy. | High | All constants `TUNABLE`; model the sink/source balance before launch; playtest. |
| ECON-RK-02 | Currency loss on accident feels punishing/gambling-adjacent. | High | Cap %, clamp to non-negative, forgiveness, recovery-not-prevention, encouraging copy (README §9). |
| ECON-RK-03 | Premium advantages drift into pay-to-win, undermining the focus purpose. | Med | `ECON-INV-05/-06`: premium = cosmetic/convenience/content only. |
| ECON-RK-04 | Reward-only taper makes the ~90-min wellbeing nudge invisible. | Med | Flagged in `ECON-BR-03` note; revisit a rest nudge if telemetry shows marathons. |
| ECON-RK-05 | Ad-reward / IAP fraud drains or inflates the economy. | Med | Server-side validation + idempotency + daily caps (`ECON-NS-04/-05`). |

## 11. Gaps & open questions

| ID | Gap | Current default |
|---|---|---|
| ECON-GAP-04 | **All tunable constants** — `base_rate`, `RAMP_FULL`, `TAPER_FLOOR`, streak floor, accident `%`s, fees, repair costs, ad caps. | Placeholders above; resolve by economy modelling + playtest. |
| ECON-GAP-05 | **Hard session ceiling** beyond the taper (cap total earnable per session)? | None for now (taper only); add if marathons persist. |
| ECON-GAP-06 | **Fuel system** scope — README lists it as a light, optional, deferred sink. | Deferred (README §11); keep non-punitive when introduced. |

## 12. Acceptance criteria (Given/When/Then)

```
ECON-AC-01  Never negative on accident loss
  Given the user has 30 Fare banked
  And a Moderate accident charges a 50% loss + a 25 Fare fee
  When the completion transaction applies costs
  Then the balance becomes exactly 0 (clamped), never negative
  And the ledger records the actual amounts removed

ECON-AC-02  Voluntary spend is blocked, not clamped
  Given the user has 10 Fare
  When they attempt a 50 Fare repair
  Then the repair is refused with recovery options
  And the balance stays 10

ECON-AC-03  Grants are idempotent
  Given a completion with Operation ID "op-123" already awarded 40 Fare
  When the same "op-123" request is retried
  Then no additional Fare is granted

ECON-AC-04  Tampered idempotent replay is rejected
  Given Operation ID "op-123" was first seen awarding 40 Fare
  When "op-123" is replayed asserting 4000 Fare
  Then the request is rejected and nothing is granted

ECON-AC-05  Ramp-up kills micro-farming without a cliff
  Given RAMP_FULL is 8 minutes
  When a Trip completes with 1 focused minute
  Then fare_earned is a small fraction of a full minute's rate (not zero-cliff, not full)
  And no streak credit is granted (below the streak floor)

ECON-AC-06  Taper is reward-only
  Given a Trip runs to 120 focused minutes
  When reward is computed
  Then minutes past 90 are weighted below 1.0
  And the avatar's Distance/animation was never slowed by the taper

ECON-AC-07  Forgiveness short-circuits the whole cost
  Given the user holds 1 Accident forgiveness
  When a Major accident is rolled
  Then no currency loss, repair cost, or lockout is applied
  And forgiveness is decremented to 0
  And the result screen states the streak saved them

ECON-AC-08  Starter cost is capped
  Given the user is on the Starter vehicle
  When a Major accident is rolled
  Then only the trip Distance plus a small fee is lost
  And no repair cost or lockout is applied
```
