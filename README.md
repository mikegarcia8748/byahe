# Biyahe — Focus & Travel Gamified Productivity App
> **Working title:** *Biyahe* (Filipino: "journey / trip"). Rename freely.
>
> **Purpose of this document:** High-level development guide and source of truth for the project's concept, mechanics, economy, and architecture. Intended to be read by an AI coding assistant (and human developers) before generating code. When in doubt, this file defines intent; implementation details may evolve.
---
## 0. Settled Architecture Decisions (read first)
These are decided. Build against them unless explicitly revisited.
- **Client:** Compose Multiplatform (CMP) — shared UI **and** logic in Kotlin, targeting Android + iOS. (Not Korge; a game engine is overkill for a UI-first app with one animated screen. Not Flutter/React Native.)
- **Backend:** Cloud Functions for Firebase, written in **TypeScript**. The client never talks to the database directly; Functions are the authoritative backend.
- **Primary database:** Cloud Firestore. (Cloud SQL / Firebase SQL Connect is **deferred** until genuinely relational/analytical needs appear — see §8.)
- **Auth:** Firebase Authentication (Google Sign-In primary). Client holds only short-lived tokens.
- **Security model:** Server-authoritative economy. Firestore Security Rules deny direct client writes to economy data; **callable functions** + **App Check** are the gatekeepers.
---
## 1. Vision
A mobile app that helps users focus on real work or tasks by wrapping a **Pomodoro-style focus timer** inside a **road-trip RPG across the Philippines**.
The user picks a task, picks a vehicle, and "travels" across a map of the Philippines while they stay focused. Their avatar physically moves and unlocks landmarks/tourist spots as they accumulate focused time. Losing focus (switching apps, opening notifications) stops the journey and risks an **accident** with real in-game consequences (lost progress, repair costs, "hospitalization" fees). The result is a productivity tool where the gameplay loop reinforces the *habit* of sustained focus.
**Core promise:** Stay focused → travel further → unlock the country → grow your garage. Lose focus → stall, and risk costly setbacks.
---
## 2. Core Concept Summary
- **Map of the Philippines**, revealed progressively (not all at once). User starts at their real location (or a chosen/entered location) and explores outward.
- **A focus session = a "trip."** One trip maps to one task / todo item.
- **Distance traveled is a function of *focused* time**, not just wall-clock time. The avatar moves only while the user stays focused.
- **Vehicles** (cars, motorcycles, etc.) have different stats (speed vs. risk vs. cost) and are collectible/customizable.
- **Landmarks / tourist spots / special places** act as stopovers and exploration rewards.
- **Distraction detection** drives an **accident-risk system** with probabilistic, scaled consequences.
- **In-game currency** is earned by focusing and spent on repairs, fuel, cosmetics, and unlocks.
- **Monetization** via non-aggressive ads (free tier), a cheap ad-removal tier, and a VIP tier with exclusive vehicles/customization.
---
## 3. Tech Stack
| Layer | Technology |
|---|---|
| Client | **Compose Multiplatform** (Kotlin, shared UI + logic) → Android + iOS. Ktor client for HTTP, kotlinx.serialization for models, coroutines/Flow for async. |
| Auth | Firebase Authentication (Google Sign-In primary; email/password optional) |
| Backend / serverless logic | **Cloud Functions for Firebase (TypeScript)** — callable functions, protected with App Check. The single gatekeeper to data. |
| Primary database | **Cloud Firestore** (documents; native to Functions; ACID transactions adequate for a per-user economy) |
| Relational DB (deferred) | Firebase **SQL Connect** (managed Cloud SQL for PostgreSQL). Only adopt when relational/analytical needs justify it — see §8. |
| Storage | Firebase Storage (assets, user-generated content if any) |
| Analytics & monetization | Firebase Analytics, Google AdMob (rewarded + non-intrusive ads), Google Play Billing / StoreKit for IAP (validated server-side) |
| Map rendering | Stylized 2D map drawn in **Compose Canvas** (not Google Maps tiles) — see §10 |
| Location | Device location / Google account address, via per-platform `expect`/`actual` |
> **Why this shape:** Compose Multiplatform keeps the whole client in Kotlin with one UI codebase (production-ready for iOS since CMP 1.8.0, May 2025). Firebase Functions is the backend — "serverless" means no server to operate, **not** no server-side logic. Firestore is primary because it is native to Functions and its transactions are sufficient for a single-player economy; pulling Cloud SQL into serverless functions adds connection-pooling friction not worth it at MVP.
---
## 4. Core Game Loop ("The Trip")
### Setup phase
1. User selects a task from their todo list (or creates one) and sets/accepts a focus duration.
2. User selects a starting point (current GPS location, saved address from Google account, or manual input).
3. App determines a destination (next unexplored landmark, or user choice).
4. User selects a vehicle (locked for the duration of the trip).
### Active phase
- Timer starts; avatar begins traveling the route; map progress animates.
- **Distance is tied to focus**, e.g.:
  ```
  distance_this_tick = base_speed[vehicle] × focus_multiplier
  ```
  `focus_multiplier` = 1.0 while focused; drops to 0 (avatar pauses) when distraction is detected.
- The avatar visibly stops when the user is not focused — direct visual feedback for the behavior being trained.
- **Authoritative note:** the client may animate optimistically, but distance/reward/accident outcomes are confirmed by the backend on trip completion (see §10).
### Completion phase
- On reaching destination / finishing the timer: a **callable function** computes and awards currency, reveals landmark, updates map progress, applies streak bonuses.
- If an accident occurred (see §5): the function applies losses and returns recovery options.
---
## 5. Distraction & Accident System
**Design principles:**
- Pause *before* punishing — a brief glance at a notification should not cause loss.
- Risk scales with **cumulative** distraction in a session, not a single lapse.
- An accident is **probabilistic**, never guaranteed — preserve user agency.
### Distraction tiers (tunable)
| Distraction this instance | Effect |
|---|---|
| 0–10 sec | Avatar pauses; no risk added; gentle "still there?" prompt |
| 10–60 sec | Avatar pauses; **risk meter** increments |
| 60 sec+ | Risk meter increments faster; may cross accident threshold |
| Return to app | Risk meter **decays gradually** over following focused minutes (does not reset instantly — prevents duck-out/duck-in gaming) |
### Accident probability
```
accident_chance = min(risk_meter / 100, 0.6)   // hard cap at 60%
```
- The accident **roll happens server-side when the user returns / on trip completion**, not while they're away (avoid anxiety-inducing "will I crash" screens during distraction, and keep the roll out of the client's reach).
### Vehicle risk profiles (makes vehicle choice meaningful)
- **Motorcycles:** higher base speed (more distance per focused minute), higher accident chance/severity.
- **Cars:** slower, safer — forgiving for distraction-prone users.
- Choice is a real tradeoff: *fast but risky* vs. *slow but stable*, not cosmetic.
### Accident outcomes (roll severity: minor / moderate / major)
| Severity | Progress | Currency | Vehicle |
|---|---|---|---|
| Minor | Lose this trip's distance; keep map progress | Small fee | Cosmetic scratch, no cost |
| Moderate | Lose trip + small % of banked currency | Medium repair cost | Unusable until repaired |
| Major | Lose trip + larger % of banked currency | High repair + "hospitalization" fee | Unusable for X hrs unless repaired/rushed |
**Hard guardrail:** Never lock a user out of play or drive their balance negative. A free, undamageable starter vehicle is always available so the user can always start another trip.
---
## 6. Economy & Currency
**Two currencies:**
- **Fare (soft currency):** earned by completing focus sessions; spent on repairs, fuel, basic customization.
- **Gems/Tokens (premium-ish):** earned slowly via streaks/achievements; purchasable; spent on cosmetics, vehicle unlocks, repair-rush.
**Earning (example):**
```
fare_earned = base_rate × focus_minutes × vehicle_efficiency × streak_bonus
```
- `streak_bonus` rewards consecutive distraction-free sessions and daily consistency → reinforces the *habit*.
- Diminishing returns past ~90 min/session to discourage unhealthy marathons (also a wellbeing measure).
**Spending sinks:**
- Repairs (loss recovery)
- Fuel/maintenance (light, optional resource management — keep non-punitive)
- Cosmetic customization (paint, decals, accessories — vanity only, no gameplay effect)
- Vehicle unlocks (gated by both currency **and** map progress, e.g. "reach Cebu to unlock the ferry")
**Integrity:** every currency change runs inside a Firestore transaction and writes an append-only ledger entry (see §8, §10). Reward-granting functions are **idempotent** (keyed by trip/operation ID) so a retried request cannot double-credit.
---
## 7. Monetization & Account Tiers
| Tier | Price | Experience |
|---|---|---|
| **Tier 1 — Free** | ₱0 | Full game **with non-aggressive ads.** Ads are primarily **rewarded/optional**: watch to recover currency when short on a repair, halve a repair-rush timer, or claim a daily-capped Gem pack. No forced interstitials between every action. |
| **Tier 2 — Ad-Free** | ~₱50 one-time | Identical gameplay, **all ads removed.** |
| **Tier 3 — VIP** | Subscription/premium | Ad-free **plus** exclusive vehicle unlocks, advanced customization, and other premium items/cosmetics. |
**Monetization guardrails (intentional, see §9):**
- Ads appear as *opt-in recovery/convenience*, never as a constant nag.
- **No paying to prevent an accident before it happens.** Paying to recover *after* (repair-rush) is acceptable; paying to make a trip risk-free is not.
- Premium advantages should be cosmetic / convenience / content-access — avoid pay-to-win that undermines the focus-training purpose.
- **All purchases (tier upgrades, currency packs) are validated server-side** in a function against Play Billing / App Store receipts before anything is granted. Never trust a client's claim of a purchase.
---
## 8. Data Model & Database Strategy
### Strategy: Firestore-first
Firestore is the **primary store**, accessed through Cloud Functions. Rationale:
- Native to Functions (Admin SDK, triggers); no DB connection-pool management.
- Multi-document **ACID transactions** are sufficient for a per-user, low-contention economy.
- Realtime listeners are available for live UI reads (currency, trip state) — used for *reads only*; all economy *writes* go through Functions.
**Cloud SQL / SQL Connect is deferred.** Bring it in only when a dataset is genuinely relational/analytical (e.g. large-scale leaderboards, cross-user analytics, complex joins). Note: SQL Connect already provides its own secure endpoints + typed SDKs, so it partly overlaps with a Functions backend — don't run both for the same dataset.
### Firestore sketch
> Illustrative, not final.
```
users/{userId}
  - displayName, email, photoURL
  - tier: "free" | "adfree" | "vip"
  - homeLocation: { lat, lng, label }
  - currency: { fare, gems }
  - currentVehicleId
  - streak: { count, lastSessionDate, accidentForgiveness }
  - createdAt, updatedAt
users/{userId}/tasks/{taskId}
  - title, notes, estimatedMinutes
  - status: "todo" | "active" | "done"
  - createdAt, completedAt
users/{userId}/trips/{tripId}
  - taskId, vehicleId
  - origin: { lat, lng }, destination: { landmarkId }
  - status: "active" | "completed" | "accident"
  - focusedSeconds, distractedSeconds, riskMeter
  - rewardFare, rewardGems
  - clientOpId        // idempotency key for the completion function
  - startedAt, endedAt
users/{userId}/garage/{vehicleId}
  - catalogVehicleId, condition, customization: { paint, decals, accessories }
  - unlockedAt
users/{userId}/transactions/{txId}        // append-only currency ledger
  - type: "earn" | "repair" | "purchase" | "ad_reward" | "unlock"
  - currency: "fare" | "gems", amount, balanceAfter, createdAt
vehicles/{catalogVehicleId}               // global catalog (read-only to clients)
  - name, class: "car" | "motorcycle" | "other"
  - baseSpeed, riskProfile, accidentSeverityMod, cost, unlockRequirement
landmarks/{landmarkId}                     // global catalog (read-only to clients)
  - name, region, lat, lng, description, imageRef
  - unlockRequirement, isSpecial
users/{userId}/mapProgress/{regionId}
  - unlockedLandmarkIds[], percentExplored
```
### Security Rules posture
- Per-user documents: readable by the owner; **economy fields not directly writable** by clients (writes go through Functions, which use the Admin SDK and bypass rules).
- Global catalogs (`vehicles`, `landmarks`): read-only to clients.
- Default deny everything else.
---
## 9. Design Principles & Wellbeing Guardrails
The app ties **real loss (currency, progress) to a probabilistic negative event (accident)**, which is structurally similar to loss-aversion / gambling-adjacent design. This is what makes consequences feel real — but it must be kept on the right side of the line so the app remains a *focus tool*, not a source of anxiety or compulsion.
- **Accident chance is capped** (≤60%) — a bad session is never an inevitable disaster.
- **Never lock the user out** — a free starter vehicle always allows another trip.
- **No pay-to-avoid-risk** — only pay-to-recover after the fact.
- **Behavior-based safety nets** over paid ones — e.g. a 7-day streak grants one "accident forgiveness."
- **Discourage unhealthy marathons** via diminishing returns past ~90 min.
- **Ads are opt-in**, never punitive or constant.
- Keep distraction feedback **encouraging, not shaming** ("still there?" not "you failed").
---
## 10. Architecture & Implementation Notes
### Overall shape (server-authoritative, thin client)
```
Compose Multiplatform client            Firebase Functions (TypeScript)        Data
────────────────────────────            ───────────────────────────────        ────
- renders UI & animations               - callable fns (auth ctx injected)     Firestore (primary)
- captures input / local trip state      - App Check verified                   (Auth = identity)
- Firebase Auth (holds token only)  ──►  - reward & accident logic         ──►  SQL Connect (deferred)
- optimistic UI; server confirms   call  - currency txns + ledger
                                          - IAP receipt validation
```
- The client holds **no privileged credentials** and computes **no economy outcomes**. It calls callable functions; the server is the source of truth.
- Use **2nd-gen** functions (Cloud Run-backed; concurrency; fewer cold starts). Consider a small min-instance count on latency-sensitive callables post-MVP.
### Auth flow
1. User signs in with Firebase Auth (Google Sign-In).
2. Client obtains a Firebase **ID token** (JWT, ~1h, auto-refreshed) and refresh token.
3. With **callable functions**, the verified auth context is injected automatically — no manual token parsing.
4. Store the refresh token in platform **secure storage** (iOS Keychain / Android Keystore / EncryptedSharedPreferences) via `expect`/`actual`.
### Secrets, tokens & keys — what goes where
- **Safe in the client (not secrets):** Firebase config (`apiKey`, `projectId`, `appId` — an identifier, not a password); public IDs like AdMob ad-unit IDs. Protect against *abuse* with App Check + Auth, not by hiding these.
- **Client-held by design (scoped, short-lived):** the user's Auth ID token + refresh token. Keep the refresh token in secure storage.
- **Server-only (never in the client build):** third-party *secret* keys (IAP/server-to-server) → **Google Secret Manager** via `defineSecret`. Admin SDK privileged access → **handled automatically by the Functions runtime** (its own service-account identity); you only deal with a service-account JSON if running Admin SDK outside Google, which we are not.
- Mental model: security is layered (**Auth** + **Security Rules** + **App Check** + **Functions**), not "hide the keys." The keys that matter never leave Google's servers.
### Focus / distraction detection
- Detect foreground/background via platform lifecycle, exposed through `expect`/`actual` (Android `Lifecycle`/`ProcessLifecycleOwner`; iOS `UIApplication` notifications). Compose Multiplatform shares the UI; lifecycle hooks are the per-platform `actual`.
- Feed time-away into the risk meter.
- **Platform reality check:** foreground/background detection is reliable; detecting *which* app was opened or reading notification contents is heavily restricted on iOS and Android. Design around "app left foreground," not deep OS surveillance.
- Treat Screen Time / Digital Wellbeing APIs as out-of-scope unless a feature truly needs them and the permission is justified.
### Avatar & travel animation (no game engine needed)
- Draw the stylized Philippines map in **Compose Canvas**; plot landmark nodes and routes.
- Animate the vehicle along a path with Compose's animation system (`Animatable`, `animate*AsState`, infinite transitions) driven by `focusedSeconds`.
- Use **Lottie** (Compose-compatible) for richer pre-baked sequences like the accident animation.
- Compose can host a custom render surface if a future screen ever needs heavier rendering — not required for MVP.
### Economy integrity (in Functions)
- Wrap every currency change in a **Firestore transaction**; append a ledger entry.
- Make reward/grant functions **idempotent** via `clientOpId` so retries can't double-credit.
- Validate IAP receipts server-side before granting tiers/currency.
### Offline / interruption handling
- Distinguish "user got distracted" from "OS interrupted the user" (incoming call, low battery, forced backgrounding). Lean lenient to avoid unfair penalties.
---
## 11. Suggested MVP Scope (Phase 1)
Keep the first build small and prove the core loop:
1. Compose Multiplatform project scaffold (Android + iOS), Google Auth + basic profile.
2. Todo list (create task, start focus session).
3. Focus timer with foreground/background detection (`expect`/`actual`).
4. **One** stylized region of the map (Compose Canvas) with ~5 landmarks.
5. Two starter vehicles (one car, one motorcycle) with differing speed/risk.
6. Risk meter + accident roll + minor/major outcomes (computed server-side).
7. Single soft currency (Fare); earn on success, lose on accident; basic repair — all via callable functions with transactional ledger.
8. Firestore Security Rules locked down; App Check enabled.
**Defer to later phases:** full national map, Gems currency, customization, ad integration, tiers/IAP, leaderboards, streak-forgiveness, fuel system, Cloud SQL / SQL Connect.
---
## 12. Open Questions / To Decide
- **Map authoring:** how to produce the stylized Compose Canvas map (hand-drawn asset vs. generated vector vs. node graph over a base illustration).
- **Firebase access from CMP:** confirm approach for any direct client SDK use (e.g. GitLive `firebase-kotlin-sdk` for Auth/realtime reads) vs. routing everything through callable functions. Economy writes stay in Functions regardless.
- **Tuning values:** `base_rate`, `base_speed`, risk thresholds, repair costs (needs playtesting).
- **iOS specifics:** Xcode build/signing setup and any native bridging for lifecycle/secure-storage/location.
- **Localization:** Filipino + English from day one, or English-first?
---
## 13. Decision Log
- Client: **Korge → Compose Multiplatform** (game engine unnecessary; UI-first app; CMP is production-ready and keeps everything in Kotlin).
- Backend: **Firebase Functions (TypeScript)** chosen; note this trades away Kotlin code-sharing between client and server (Functions support Node/TS and Python only).
- Database: **Postgres-primary → Firestore-primary**, because Firestore transactions suffice for a single-player economy and Cloud SQL-from-Functions adds connection-pool friction; SQL Connect deferred.
- Security: client talks only to callable functions + App Check; Security Rules deny direct economy writes.
---
*This README is a living guide. Update it as mechanics and architecture decisions are finalized.*
#   b y a h e  
 