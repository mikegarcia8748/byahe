# Biyahe — Client Architecture Guide

> Companion to [README.md](README.md). The README defines *what* Biyahe is and the backend/security
> posture (server-authoritative economy on Firebase Functions). **This document defines how the
> Compose Multiplatform client is structured**: modularization, Clean Architecture layering, the
> patterns/libraries chosen, the alternatives considered, and the testing strategy.
>
> When README §0 (Settled Architecture Decisions) and this file conflict, the README wins on
> *product/backend* intent; this file owns *client structure*.

---

## 0. TL;DR — what we're building toward

- **Modularization:** Hybrid — **feature modules (vertical slices)** + **layered `core` modules**, with the
  **domain layer as pure-Kotlin modules** so the dependency rule is enforced by the compiler, not by discipline.
- **Pattern:** **Unidirectional Data Flow (UDF)**. **MVI** for stateful/game screens (the Trip), light **MVVM**
  for simple CRUD screens (Tasks). One mental model, two intensities.
- **DI:** **Koin** (KMP-pragmatic, no iOS KSP friction). Compile-time DI (kotlin-inject) is the alternative.
- **Navigation:** **Jetpack Navigation Multiplatform** (official, type-safe). **Decided.** (Decompose was the
  considered alternative — see §4.4.)
- **Local data:** **SQLDelight** (typed, KMP, testable) for the offline trip/task cache; **multiplatform-settings**
  or KMP **DataStore** for prefs; secure storage via `expect`/`actual` (Keychain / Keystore).
- **Backend access:** reads via **GitLive `firebase-kotlin-sdk`** (Auth + Firestore listeners + App Check);
  **all economy writes through callable Functions**. (README §12 open question — resolved here as the default.)
- **Build hygiene:** **`build-logic` convention plugins** from day one. With ~15+ modules this is not optional.
- **iOS reality:** one **umbrella module** (`:shared`) aggregates and **re-exports** features to the iOS framework.

---

## 1. Why Clean Architecture + multimodule here (and the one real reason)

The testability win is concrete and worth being precise about, because it's the justification for the extra
module overhead:

> **A Gradle module boundary turns the Clean Architecture "dependency rule" from a guideline into a compile error.**

If `:core:model` and the domain layer are **pure Kotlin modules with zero Android/Firebase/Compose dependencies**,
then nothing in domain *can* import a `Context`, a `FirebaseFirestore`, or a `@Composable`. Use cases and the
risk/accident math become plain functions over plain data — tested in milliseconds in `commonTest`, no emulator,
no Robolectric, no mocking framework required.

That is the leverage. Everything else (feature isolation, parallel builds, reuse) is a bonus. So the **non-negotiable
core of the design** is: *domain is pure and depends only on interfaces it owns.* The rest of the modularization is
tunable.

---

## 2. Recommended module graph

```
Byahe/
├── build-logic/                      # Gradle convention plugins (not a published module)
│   └── convention/                   #   byahe.kmp.library, byahe.feature, byahe.android.app, byahe.compose ...
│
├── androidApp/                       # Android Application — Activity, DI bootstrap, manifest, AdMob/Billing wiring
├── iosApp/                           # Xcode project — consumes the :shared framework, StoreKit bridging
│
├── shared/                           # UMBRELLA. Produces the iOS "Shared" framework. Hosts root composable,
│                                     #   navigation host, DI startup. Depends on + RE-EXPORTS feature modules.
│
├── core/
│   ├── model/                        # Pure Kotlin. Entities & value objects: Trip, Vehicle, Money, RiskMeter,
│   │                                 #   Landmark, Task, Currency. NO dependencies. The most-depended-on module.
│   ├── common/                       # Result/Either, dispatchers, Clock, error types, coroutine utils.
│   ├── domain/                       # (optional) cross-feature use cases & repository INTERFACES that
│   │                                 #   multiple features share. Pure Kotlin. Often features own their own.
│   ├── designsystem/                 # Compose theme, color, type, spacing, reusable atoms/molecules.
│   ├── ui/                           # Shared non-trivial UI: map Canvas primitives, animation helpers, Lottie host.
│   ├── data/                         # Repository IMPLEMENTATIONS that span features; mapping DTO<->domain.
│   ├── network/                      # Ktor client config + the callable-Functions wrapper (one typed entry point).
│   ├── firebase/                     # GitLive Firebase: Auth, Firestore read streams, App Check init.
│   ├── database/                     # SQLDelight schema + drivers (expect/actual). Offline trip/task cache.
│   ├── datastore/                    # Prefs (multiplatform-settings/DataStore) + SecureStorage expect/actual.
│   └── testing/                      # Fakes, fixtures, TestDispatcher rules, fake repos. test-only, depended on by *Test.
│
└── feature/                          # Vertical slices. Each is a KMP module with ui/ + presentation/ + domain/ + data/ packages.
    ├── auth/                         # Google Sign-In, token lifecycle, secure storage of refresh token.
    ├── onboarding/                   # First-run, home-location pick.
    ├── tasks/                        # Todo CRUD (simple MVVM).
    ├── trip/                         # ★ THE CORE LOOP. Timer, distraction detection, risk meter, map animation,
    │                                 #   optimistic UI, trip state machine, completion call. The MVI heavyweight.
    ├── garage/                       # Vehicles, condition, customization.
    ├── store/                        # Currency, repairs, repair-rush, AdMob rewarded, IAP purchase flow.
    └── profile/                      # Settings, tier status, streak display.
```

### Dependency rules (enforced by what each module is *allowed* to depend on)

```
androidApp ─┐
            ├─► shared ─► feature:* ─► core:{model, common, designsystem, ui, network, firebase, database, datastore}
iosApp  ────┘                    └─► (domain interfaces in core:domain or own package)

core:model            depends on NOTHING (pure Kotlin)
core:common           depends on core:model (+ coroutines)
feature:* domain code depends on core:model, core:common only  ← the pure, fast-test layer
feature:* data code   depends on its own domain interfaces + core:{network, firebase, database}
feature:* ui code     depends on its own presentation + core:{designsystem, ui}
features NEVER depend on each other  ← cross-feature contracts live in core:domain
core:testing          depended on ONLY by test source sets
```

The two rules that keep this honest:
1. **No feature → feature edges.** If `trip` needs a vehicle, it depends on a `VehicleRepository` *interface*
   in `core:domain`, not on `:feature:garage`. Prevents the module graph from collapsing into a ball.
2. **Inward-only dependencies.** ui → presentation → domain → model. Data implements domain interfaces
   (dependency inversion), so domain never points at data or framework.

---

## 3. Clean Architecture layering inside a feature

Each feature is a **vertical slice**. Layers are *packages* inside the module by default, promoted to *sub-modules*
only when a feature gets big (the Trip is the likely candidate). This avoids a 30-module explosion while keeping the
boundaries real.

```
feature/trip/src/commonMain/kotlin/.../trip/
├── domain/                 # PURE. Use cases + repository interfaces + the risk/accident math.
│   ├── model/              #   Trip-specific value objects (RiskMeter, DistractionEvent, AccidentRoll)
│   ├── ComputeRiskUseCase.kt
│   ├── RollAccidentUseCase.kt        # deterministic given an injected RandomSource → unit-testable
│   ├── TripRepository.kt             # interface
│   └── FocusClock.kt                 # interface over time → tests inject a fake clock
├── data/                   # Implements TripRepository. Talks to core:firebase / core:network / core:database.
│   ├── TripRepositoryImpl.kt
│   ├── dto/ + mapper/
│   └── FunctionsTripDataSource.kt    # callable function: completeTrip(clientOpId)
├── presentation/           # MVI: TripState, TripIntent, TripEffect, TripViewModel(reducer).
│   ├── TripState.kt        #   pure data; reducer is a pure (state, intent) -> state function
│   ├── TripViewModel.kt
│   └── DistractionObserver.kt        # expect/actual lifecycle bridge feeds intents
└── ui/                     # @Composable screens. Renders state, emits intents. No logic.
    ├── TripScreen.kt
    └── MapCanvas.kt
```

**Why MVI for Trip specifically:** the trip *is* a finite state machine (`Setup → Active ↔ Distracted → Completed |
Accident`). MVI's `(State, Intent) -> State` reducer models that exactly, the state is one serializable object
(trivially restorable across process death / app backgrounding — which happens constantly here because backgrounding
is the distraction signal), and the reducer is a pure function you can table-test. **Why light MVVM for Tasks:**
CRUD has no interesting state machine; full MVI ceremony there is just boilerplate.

---

## 4. Key decisions, alternatives, pros & cons

### 4.1 Modularization strategy

| Option | Pros | Cons |
|---|---|---|
| **Single `:shared` (status quo)** | Zero setup; simplest iOS framework story; fast to start | Domain can import framework → dependency rule unenforced; build scales poorly; tests pull in everything |
| **Layer-only** (`:domain`, `:data`, `:presentation`) | Enforces Clean layers; few modules | All features tangled in each layer module; poor feature isolation; merge conflicts; can't ship/disable a feature |
| **Feature-only** (`:feature:trip`, …; no core split) | Great feature isolation | Cross-cutting code (theme, network) duplicated or dumped in umbrella; no pure-domain enforcement |
| **★ Hybrid: features + layered core (recommended)** | Feature isolation **and** enforced pure domain; matches Google's *Now in Android*; parallel builds; precise test scope | Most modules to manage → **mitigated by convention plugins**; iOS re-export ceremony |

**Recommendation:** Hybrid. It's the only option that delivers the testability you're asking for *and* scales.
Start with features as vertical slices (layers = packages); split a feature into `:feature:x:domain/data/ui`
sub-modules only when it earns it.

### 4.2 Presentation pattern

| Option | Pros | Cons |
|---|---|---|
| **MVVM** (ViewModel + StateFlow) | Familiar; less boilerplate; fine for CRUD | Mutable state scattered across fields; harder to model state machines; effects ad hoc |
| **★ MVI / UDF** | Single immutable state; pure reducer = trivially unit-testable; ideal for the Trip FSM; free state restoration | Boilerplate for trivial screens; learning curve |
| **MVU / Decompose components** | Logic fully outside Compose → testable without Compose runtime; strong lifecycle model | Another paradigm; smaller community; more upfront design |

**Recommendation:** UDF everywhere, intensity scaled per screen — MVI for Trip/Store/Garage, lean MVVM for
Tasks/Profile. Optionally adopt **Orbit-MVI (multiplatform)** to cut MVI boilerplate, or hand-roll a tiny
`reduce()` base. Don't mix three patterns.

### 4.3 Dependency injection

| Option | Pros | Cons |
|---|---|---|
| **★ Koin** | KMP-first; no KSP/kapt on iOS; runtime modules are easy; huge community | Runtime resolution → errors at startup not compile time; reflection-ish |
| **kotlin-inject (+ anvil)** | Compile-time safety; fast; no runtime container | KSP setup per module; steeper; more ceremony in KMP |
| **Manual / service locator** | Zero deps; fully explicit | Wiring boilerplate grows painfully with module count |

**Recommendation:** **Koin**. For a solo/small team on KMP it's the pragmatic default and the iOS story is painless.
Revisit kotlin-inject only if startup-time DI errors become a recurring pain. Each feature exposes a `featureModule`
that the umbrella aggregates.

### 4.4 Navigation

| Option | Pros | Cons |
|---|---|---|
| **★ Jetpack Navigation Multiplatform** | Official, now stable in CMP; type-safe routes; least to learn | Tied to Compose; back-stack logic harder to unit-test in isolation |
| **Decompose** | Navigation + state holders live outside Compose → unit-testable; excellent process-death handling; great for deep state | New paradigm (components); more boilerplate; smaller ecosystem |
| **Voyager** | Very low boilerplate; screen-as-class | Less "official"; opinionated; some KMP edge cases |

**Decision: Jetpack Navigation Multiplatform.** Official, type-safe routes, lowest learning curve, and switching is
costly later so it's locked in now. Decompose was the principled alternative (testable navigation/state outside
Compose); if back-stack/screen-state unit testing ever becomes a real pain point we revisit, but the default holds.

### 4.5 Client ↔ backend access (README §12 open question)

| Option | Pros | Cons |
|---|---|---|
| **★ GitLive `firebase-kotlin-sdk` for reads + callable Functions for writes** | One Kotlin API in `commonMain`; real-time Firestore listeners for currency/trip; App Check supported | Third-party wrapper over native SDKs; lags upstream features occasionally |
| **Ktor → HTTPS callable endpoints only** | No Firebase client SDK; full control; trivially mockable in tests | Lose realtime listeners; reimplement Auth token attach + App Check headers by hand |
| **Per-platform official Firebase SDKs via expect/actual** | Closest to first-party; every feature available | You write/maintain the KMP bridge twice; most effort |

**Recommendation:** GitLive for **reads + identity** (Auth, Firestore snapshots, App Check), and route **every
economy write through a typed callable-Functions wrapper** in `core:network`. This honors README §0/§10 (server is
authoritative; client computes no economy outcomes) while keeping the live-read UX. Isolate the SDK behind
`core:firebase` so the rest of the app — and tests — only see interfaces.

### 4.6 Local persistence

| Option | Pros | Cons |
|---|---|---|
| **★ SQLDelight** | Typed SQL, KMP-native, in-memory driver for fast tests; great for trip/task cache & ledger mirror | SQL by hand; schema migrations to manage |
| **Room KMP** | Familiar annotations; Google-backed; now multiplatform | Newer on KMP; KSP per module; heavier |
| **Realm/Realm Kotlin** | Object DB, reactive | Maintenance/ownership uncertainty; opinionated |

**Recommendation:** SQLDelight for structured offline state; **multiplatform-settings** or KMP **DataStore** for
flags/prefs; secure storage (`Keychain`/`Keystore`/`EncryptedSharedPreferences`) via a `SecureStorage` `expect`/`actual`
in `core:datastore`. Keep the offline cache a *read/optimistic-UI* mirror — the Firestore + Functions remain source of
truth per the README.

---

## 5. KMP-specific gotchas to design around

- **iOS sees only the umbrella's exported API.** The iOS framework is produced by one module (`:shared`). Any
  feature type iOS must touch has to be `api`-exposed and `export()`-ed through `:shared`. Practically: iOS calls a
  small set of root entry points (the root composable + a few coordinators) rather than reaching into features.
  Design the umbrella's public surface deliberately and keep it small.
- **Convention plugins are mandatory at this module count.** `build-logic` plugins (`byahe.kmp.library`,
  `byahe.feature`, `byahe.compose`, `byahe.android.application`) keep 15+ `build.gradle.kts` files from drifting.
  Set this up *before* creating the modules, not after.
- **`commonTest` is your fast lane.** Pure domain + reducer tests run on JVM in `commonTest` with `kotlin.test` +
  **Turbine** (Flow assertions) + a fake `Clock`/`RandomSource`. Reserve Android instrumentation / Compose UI tests
  for the genuinely UI-bound cases. The accident roll, risk decay, fare math, and trip reducer should be 100%
  `commonTest`.
- **Determinism for the economy math.** Inject `RandomSource` and `Clock` interfaces into use cases. The README's
  `accident_chance`, risk decay, and `fare_earned` formulas then become exhaustively table-testable, and you can
  mirror the same logic the Functions backend runs to keep optimistic UI honest.
- **Type-safe project accessors are already on** (`settings.gradle.kts`), so modules reference each other as
  `implementation(projects.core.model)` — clean and refactor-safe.

---

## 6. Testing strategy mapped to the module graph

| Layer / module | Test type | Where | Tools |
|---|---|---|---|
| `core:model`, feature `domain/` | Pure unit (fast, no platform) | `commonTest` | kotlin.test, fake Clock/Random |
| feature `data/` | Repository tests w/ fakes | `commonTest` | fakes from `core:testing`, in-memory SQLDelight, Turbine |
| feature `presentation/` | Reducer / ViewModel tests | `commonTest` | Turbine, fake use cases, TestDispatcher |
| `core:network`, `core:firebase` | Contract tests w/ fakes | `commonTest` | Ktor MockEngine, fake Firebase facade |
| feature `ui/` | Compose UI tests | `androidUnitTest` / instrumented | Compose UI test, screenshot (optional) |

The point of the whole structure: **the logic that can lose a user real progress (risk, accidents, currency) lives in
pure modules and is tested without a device.**

---

## 7. Migration path from the current scaffold

The repo is a clean CMP template, so this is low-risk and incremental:

1. **Add `build-logic`** with one `byahe.kmp.library` convention plugin; apply it to `:shared`. No behavior change.
2. **Extract `core:model` + `core:common`** out of `:shared`. Move the placeholder `Greeting`/`Platform` demo aside.
3. **Stand up `core:designsystem`** (theme) and `core:firebase` (Auth + App Check init).
4. **Build the first vertical slice — `:feature:auth`** end to end (ui→presentation→domain→data) to validate the
   pattern, DI wiring, and iOS re-export before replicating it.
5. **Build `:feature:tasks`** (simple) then **`:feature:trip`** (the core loop) — these prove MVI + the pure economy
   math + the callable-Functions write path.
6. Keep `:shared` as the umbrella: it depends on and re-exports features, hosts the nav host + root composable, and
   initializes Koin. `:androidApp` and `iosApp` stay thin.

This sequences directly onto README §11 (MVP Phase 1): Auth → Tasks → Trip → one map region → two vehicles → risk/
accident → single Fare currency, all behind callable Functions.

### Pragmatic MVP de-scoping (don't over-engineer on day one)
You do **not** need all ~20 modules to start. Minimum viable module set that still gives you the testability win:
`build-logic`, `core:model`, `core:common`, `core:designsystem`, `core:firebase`, `core:testing`, `:feature:auth`,
`:feature:tasks`, `:feature:trip`, plus the umbrella `:shared`. Add `garage/store/profile` and `core:database` etc.
as the features in README §11's "defer" list come online.

---

## 8. Open items to confirm

- **MVI library vs hand-rolled** (Orbit-MVI multiplatform vs a tiny in-repo base).
- ~~**Navigation:** lock in Jetpack Nav MP vs Decompose.~~ **Decided: Jetpack Navigation Multiplatform** (§4.4).
- **Whether `core:domain` exists** as a shared module, or cross-feature contracts live in `core:model` only
  (depends on how much features actually share).
- Tuning values for the testable economy formulas (README §12) — feed these into the `commonTest` tables.
