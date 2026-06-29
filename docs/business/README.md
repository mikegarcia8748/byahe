# Biyahe — Business Specifications

> **Purpose:** the *business* source of truth — logic, flows, rules, edge cases, negative scenarios, risks,
> gaps, and misalignments — independent of code. Companion to [README.md](../../README.md) (product/backend
> intent) and [ARCHITECTURE.md](../../ARCHITECTURE.md) (client structure). When this folder and the README
> disagree on a *rule*, that is a **misalignment to resolve**, not a thing to silently override — log it.
>
> **Audience:** product, designers, and the AI/human engineers who will turn these into tests and Functions.

---

## How these docs are organized

**Per-feature specs are co-located with their (future) module** under `feature/<name>/BUSINESS.md`.
**Cross-cutting rules that span features live here** in `docs/business/`, so a shared rule is defined *once*
and referenced — not copy-pasted into five feature docs where it would drift.

| Doc | Scope |
|---|---|
| [glossary.md](glossary.md) | Shared vocabulary. Every term used with a capital letter elsewhere is defined here. |
| [trust-boundary.md](trust-boundary.md) | Client-vs-server authority. The rule **every** economy-touching feature obeys. (`TRUST-*`) |
| [economy-invariants.md](economy-invariants.md) | Currency/progress rules spanning features: never-negative, idempotent, append-only ledger. (`ECON-*`) |
| [distraction-accident-model.md](distraction-accident-model.md) | The canonical risk/accident system. Owned here; referenced by Trip, Garage, Store. (`DAM-*`) |
| [risk-register.md](risk-register.md) | Rolled-up index of every `*-RK-*` item across all docs, prioritized. |
| **Feature specs** | |
| [feature/trip/BUSINESS.md](../../feature/trip/BUSINESS.md) | The core game loop. ✅ written (pilot). (`TRIP-*`) |
| feature/auth/BUSINESS.md | _planned_ |
| feature/onboarding/BUSINESS.md | _planned_ |
| [feature/tasks/BUSINESS.md](../../feature/tasks/BUSINESS.md) | The todo layer + Trip entry point. ✅ written. (`TASK-*`) |
| feature/garage/BUSINESS.md | _planned_ |
| feature/store/BUSINESS.md | _planned_ |
| feature/profile/BUSINESS.md | _planned_ |

> **Status:** This is a **pilot pass** covering Trip + all cross-cutting docs, to prove the template before
> replicating across features. Treat the remaining feature docs as not-yet-written, not as "no rules."

---

## The per-feature template

Every `feature/<name>/BUSINESS.md` uses these sections in this order:

1. **Purpose & Scope** — what it owns; an explicit **Not in scope** list.
2. **Key Concepts** — feature-local terms (link to [glossary.md](glossary.md)).
3. **Actors & Triggers** — who/what starts each flow.
4. **Business Rules** — numbered, atomic, testable. Each tagged with **authority** (Client / Server / Shared) and a **Source** (README §, or "new").
5. **Flows** — happy path + alternates, step by step.
6. **States & Transitions** — a state table with guards (for stateful features).
7. **Contracts (business-level)** — what it reads vs. writes-via-Function; who owns each outcome.
8. **Economy Effects** — currency/progress impact (link to [economy-invariants.md](economy-invariants.md)).
9. **Edge Cases** — boundary / unusual-but-valid conditions.
10. **Negative Scenarios** — errors, offline, interruption, abuse/gaming, races + expected handling.
11. **Risks** — product / UX / wellbeing / security / technical, with severity + mitigation.
12. **Gaps & Open Questions** — unresolved decisions; things needing playtesting/tuning.
13. **Misalignments & Tensions** — README intent vs. platform / server / other-feature reality.
14. **Wellbeing Guardrails** — feature-specific application of README §9.
15. **Acceptance Criteria** — Given/When/Then scenarios that pin the important rules.

---

## ID & traceability scheme

Every item gets a **stable ID** so it can be referenced from a test name, a Function comment, the risk
register, or another doc. **IDs are append-only: never renumber, never reuse a retired ID.**

| Prefix | Meaning | Example |
|---|---|---|
| `<AREA>-BR-NN` | Business Rule | `TRIP-BR-04` |
| `<AREA>-INV-NN` | Invariant (must *always* hold) | `ECON-INV-01` |
| `<AREA>-FL-NN` | Flow | `TRIP-FL-02` |
| `<AREA>-ST-NN` | State | `TRIP-ST-03` |
| `<AREA>-EC-NN` | Edge case | `TRIP-EC-07` |
| `<AREA>-NS-NN` | Negative scenario / failure mode | `TRIP-NS-02` |
| `<AREA>-RK-NN` | Risk | `TRIP-RK-01` |
| `<AREA>-GAP-NN` | Gap / open question | `TRIP-GAP-05` |
| `<AREA>-AC-NN` | Acceptance criterion (Gherkin) | `TRIP-AC-03` |

**Areas:** `TRIP`, `AUTH`, `TASK`, `GARAGE`, `STORE`, `PROFILE`, `ONBOARD` (features); `TRUST`, `ECON`,
`DAM` (cross-cutting).

**Authority tag** on every rule — the single most important column in this whole project:

- **Server** — the server is the source of truth; the client must not compute or assert this. (Economy, accident roll.)
- **Client** — purely local; no economy consequence (animation, prompts, timer display).
- **Shared** — client proposes/optimistically shows, **server confirms and wins on conflict**.

---

## Conventions

- **Decision tables** for tabular logic (distraction tiers, accident severity, tier benefits).
- **Given/When/Then** for acceptance criteria and negative scenarios — they map straight to `commonTest`.
- **State tables** (not just prose) for anything with a lifecycle.
- A **Gap** is a real open decision, not a TODO; closing one usually means adding/editing a `BR` and an `AC`.
- A **Misalignment** is a place where two sources of intent conflict; it must name both sides and a proposed resolution.
- Keep rules **atomic** — one assertion each, so one test can cover one rule.
