# Glossary

> Shared vocabulary for all business specs. If a term is used with a specific meaning anywhere in the docs,
> it is defined here. Keep definitions **business-level** — no data schemas, no code.

| Term | Definition |
|---|---|
| **Trip** | One focus session represented as a journey. Maps 1:1 to a single **Task**. The unit of play and the unit of reward. |
| **Focus session** | The real-world activity a Trip wraps: the user concentrating on a Task for a set duration. |
| **Task** | A user todo item. Selecting/creating one is the entry point to a Trip. Has a status (todo / active / done). |
| **Focused time** | Wall-clock time during which the app is in the foreground and the session is running. The only thing that produces **Distance** and reward. |
| **Distracted time** | Time the app spent **not** in the foreground during an active Trip. Measured as the gap between a background event and the next foreground event. |
| **Distraction** | An instance of the app leaving the foreground during an active Trip. The **only reliably observable** distraction signal (see [trust-boundary.md](trust-boundary.md)); we cannot see *which* app was opened or read notifications. |
| **Distraction event** | A single background→foreground episode, characterized by its duration, feeding the **Risk Meter**. |
| **OS interruption** | A distraction caused by the system rather than the user's choice — incoming call, low-battery dialog, system permission prompt, forced backgrounding. Treated leniently. |
| **Focus multiplier** | Multiplier on travel speed: `1.0` while focused, `0` while distracted (avatar pauses). |
| **Tick** | A unit of simulated travel time used to accrue Distance: `distance_this_tick = base_speed[vehicle] × focus_multiplier`. |
| **Distance** | Simulated progress along the route, a function of Focused time and vehicle **base speed**. *Not* wall-clock time. |
| **Risk Meter** | A 0–100 session-scoped accumulator of cumulative distraction. Drives accident probability. Decays gradually while focused; never resets instantly. |
| **Accident** | A probabilistic negative event rolled **server-side** on return/completion, with scaled consequences. |
| **Accident roll** | The server-side random determination of whether an Accident occurs and its **Severity**. Client never holds the RNG or the mapping. |
| **Severity** | Accident magnitude: **minor / moderate / major**, each with defined Progress, Currency, and Vehicle consequences. The worst severity possible is **gated by the Risk Meter band** at roll time (`DAM-BR-09`); the vehicle's severity modifier weights the outcome *within* the allowed band. |
| **Accident chance** | `min( (Risk Meter / 100) × vehicleRiskMult , 0.6)` — the vehicle modifier is applied **inside** the cap, so **60% is an absolute ceiling for every vehicle** (a risky vehicle reaches it sooner, never exceeds it). See `DAM-BR-07`. |
| **Vehicle** | A collectible/ownable means of travel with stats: base speed, risk profile, accident-severity modifier, cost, unlock requirement. |
| **Vehicle class** | car / motorcycle / other. Drives the speed-vs-risk tradeoff. |
| **Starter vehicle** | A free, **undamageable** vehicle always available so the user can always start another Trip. The hard floor of the "never lock out" guarantee. |
| **Garage** | The user's owned vehicles and their condition/customization. |
| **Condition** | A vehicle's repair state. A damaged vehicle may be unusable until repaired. |
| **Customization** | Cosmetic-only changes (paint, decals, accessories). **No gameplay effect.** |
| **Landmark** | A tourist spot / special place; a Trip destination and exploration reward. Unlocked by reaching it. |
| **Region** | A grouping of Landmarks; the map is revealed region by region. |
| **Map progress** | Which Landmarks/regions a user has unlocked and how much is explored. |
| **Fare** | Soft currency. Earned by completing Trips; spent on repairs, fuel, basic customization. |
| **Gems / Tokens** | Premium-ish currency. Earned slowly (streaks/achievements) or purchased; spent on cosmetics, unlocks, repair-rush. |
| **Repair** | Spending currency to restore a damaged vehicle's Condition. |
| **Repair-rush** | Paying (currency / ad / Gems) to shorten or skip a repair/lockout timer. A **pay-to-recover** mechanic — allowed; **pay-to-avoid** is not. |
| **Hospitalization fee** | An extra cost attached to a **major** Accident. |
| **Streak** | Count of consecutive distraction-free / daily-consistent sessions. Drives `streak_bonus` and Accident forgiveness. |
| **Accident forgiveness** | A behavior-earned safety net (e.g. one per 7-day streak) that negates one Accident's consequences. |
| **Diminishing returns** | Reward tapering past ~90 min/session, both an anti-grind and a wellbeing measure. |
| **Ledger** | The append-only record of every currency change (type, amount, balance-after, timestamp, source op). |
| **Operation ID / `clientOpId`** | Idempotency key for a reward/grant/completion. Lets the server dedupe retries so nothing double-credits. |
| **Callable function** | A server endpoint (App Check + Auth verified) that is the **only** way the client mutates economy data. |
| **App Check** | Attestation that requests come from a genuine app build, guarding the callable functions against abuse. |
| **Optimistic UI** | Client showing a likely outcome (e.g. animated Distance) before the server confirms. Must reconcile to server truth. |
| **Server authority** | The principle that the server, not the client, decides economy/accident outcomes (README §0, §10). |
| **Tier** | Account level: **free** (ads) / **adfree** / **vip**. |
| **Orphaned Trip** | An active Trip that was never completed (app killed, never reopened) and needs a lifecycle resolution. |
