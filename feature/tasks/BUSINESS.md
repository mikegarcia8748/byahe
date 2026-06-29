# Tasks — Business Specification

> **Area code:** `TASK` · **Module:** `:feature:tasks` · **Status:** spec v1 · **Source:** README §2, §4, §8
>
> The todo layer and the entry point to a Trip. Deliberately the *simple* feature — but it carries one
> architectural decision (client-direct writes) that sets a precedent for all non-economy data.
>
> **Depends on:** [trust-boundary.md](../../docs/business/trust-boundary.md) (`TRUST-*`),
> [glossary.md](../../docs/business/glossary.md). **Hands off to** [feature/trip/BUSINESS.md](../trip/BUSINESS.md)
> (`TRIP-BR-01` one trip ↔ one task).

---

## 1. Purpose & Scope

**Owns:** Task CRUD (create / read / update / delete / complete), the task list, the status lifecycle
(`todo | active | done`), and the **Task → Trip handoff** (pick a task to start a Trip; pass its estimate as the
default focus duration).

**Not in scope:** the Trip itself, focus timing, distraction, economy (all → Trip/`DAM`/`ECON`). Recurring tasks,
subtasks/checklists, and priority/tags are **deferred** (`TASK-GAP-01..03`).

## 2. Key Concepts

See [glossary.md](../../docs/business/glossary.md): **Task**, **Trip**, **estimatedMinutes** (README data model:
`title, notes, estimatedMinutes, status, createdAt, completedAt`).

## 3. Actors & Triggers

| Actor | Triggers |
|---|---|
| **User** | create / edit / delete / reorder tasks; select a task to start a Trip; mark a task done / reopen |
| **Trip functions (server)** | flip a task `todo → active` on `startTrip`, and `active → todo` on trip end/abandon (status coupled to the single active Trip) |

---

## 4. Business Rules

| ID | Rule | Authority | Source |
|---|---|---|---|
| TASK-BR-01 | A Task has status **`todo` / `active` / `done`**. | Shared | §8 |
| TASK-BR-02 | A Task may have **many Trips** over its lifetime. Finishing a Trip **never auto-completes** the Task — completion is a manual user action. *(Decision ①.)* | Shared | new |
| TASK-BR-03 | Task **content** (title, notes, estimate; create, delete, manual done/reopen) is written **client-direct to Firestore**, gated by Security Rules to the owner. **No callable Function required** — tasks are non-economy data. *(Decision ②; `TRUST-BR-10`.)* | Client | §8, `TRUST` |
| TASK-BR-04 | A Task's **`estimatedMinutes` pre-fills the default focus duration** at Trip setup; the user can override it. *(Decision ③.)* | Client | new |
| TASK-BR-05 | While a Task has an **ACTIVE Trip, it cannot be edited or deleted** (locked), mirroring the vehicle lock (`TRIP-BR-04`). *(Decision ④.)* | Server-enforced | new |
| TASK-BR-06 | **Trip-coupled status transitions are server-driven:** `startTrip` sets the Task `active`; trip end/abandon returns it to `todo`. The client **cannot** set `active` itself, because the single-active-trip rule is server-authoritative (`TRIP-BR-02`). | Server | `TRIP-BR-02` |
| TASK-BR-07 | Because only one Trip is ACTIVE per account, **at most one Task is `active`** at a time. | Server | `TRIP-BR-02` |
| TASK-BR-08 | Manual **done** is allowed only when the Task is **not `active`** (end/abandon the Trip first). A `done` Task may be **reopened** to `todo`. | Client | new |
| TASK-BR-09 | **Task completion grants no currency.** All rewards come from focus/Trips, never from completing todos — this prevents farming via task churn. | Server | §6 (intent) |
| TASK-BR-10 | Starting a Trip on a `done` Task **reopens** it (`done → active`); starting requires a valid, non-`active` Task. | Shared | new |
| TASK-BR-11 | Tasks are **owner-scoped**: readable and writable only by their owner (Security Rules). | Server | §8 |

---

## 5. Flows

**`TASK-FL-01` Create.** User adds a task (title, optional notes/estimate) → client-direct write → appears via
listener. Works offline (Firestore offline persistence), syncs later (`TASK-EC-06`).

**`TASK-FL-02` Start a Trip from a task.** User picks a `todo` task → Trip setup opens with the focus duration
pre-filled from `estimatedMinutes` (`TASK-BR-04`) → `startTrip` flips the task to `active` (`TASK-BR-06`).

**`TASK-FL-03` Trip ends.** `completeTrip`/abandon returns the task to `todo` (`TASK-BR-06`). **No "mark done?"
prompt** — completion is manual (Decision ①). The user marks it done separately when the work is truly finished.

**`TASK-FL-04` Complete (manual).** From the list, user marks a non-`active` task `done` (`TASK-BR-08`); no reward
(`TASK-BR-09`).

**`TASK-FL-05` Edit / delete.** Allowed only when not `active` (`TASK-BR-05`); otherwise the action is blocked
with an explanation.

---

## 6. States & Transitions

| From | Event | Guard | To |
|---|---|---|---|
| (none) | create | valid title | todo |
| todo | start Trip | no other active Trip | active *(server, `startTrip`)* |
| active | trip ends / abandoned | — | todo *(server)* |
| todo | mark done | — | done |
| done | reopen | — | todo |
| done | start Trip | no other active Trip | active *(reopen + start, `TASK-BR-10`)* |
| todo / done | delete | not `active` | (deleted) |

`active` is **only ever entered/left by the server** (Trip functions). The client never writes `active`.

## 7. Contracts (business-level)

**Reads (Firestore listener, owner-only):** the task list + statuses.

**Writes:**
- **Client-direct (owner, Security-Rules-gated):** create, edit content, delete, set status ∈ {`todo`, `done`}, reorder.
- **Server (Trip functions, Admin SDK):** set status `active` / clear it; enforce the active-trip lock.

**Security Rules posture (business-level):** owner-only read/write; **client-writable `status` restricted to
`todo`/`done`** (never `active`); **edit/delete denied while `status == active`** (a single-document check — robust
because the client can't set `active`); schema validation on field types/lengths (`TASK-GAP-06`).

## 8. Economy Effects

**None.** Tasks never touch currency (`TASK-BR-09`). This is deliberate: rewarding todo-completion would create a
farming vector orthogonal to focus. Reward lives entirely in Trip/`ECON`.

## 9. Edge Cases

| ID | Scenario | Handling |
|---|---|---|
| TASK-EC-01 | Edit/delete attempted during an active Trip. | Blocked (`TASK-BR-05`); UI explains "finish or abandon the trip first." |
| TASK-EC-02 | Delete a task that has **past (completed) Trips**. | Allowed; each Trip stores a **task-title snapshot** taken at `startTrip`, so trip history survives task deletion. |
| TASK-EC-03 | Two devices edit the same task concurrently (client-direct). | Last-write-wins / field-level merge; acceptable for non-economy data (`TASK-RK-03`). |
| TASK-EC-04 | Start a Trip on a `done` task. | Reopens to `active` (`TASK-BR-10`). |
| TASK-EC-05 | `estimatedMinutes` empty/zero at setup. | Trip setup falls back to a default duration; user sets it. |
| TASK-EC-06 | Create/edit while offline. | Works via Firestore offline persistence; syncs on reconnect. |
| TASK-EC-07 | Active task on Device A, viewed on Device B. | B sees it `active` and locked via the listener (server-set status). |

## 10. Negative Scenarios

| ID | Scenario | Handling |
|---|---|---|
| TASK-NS-01 | Client writes another user's task. | Denied — owner-only Security Rules (`TASK-BR-11`). |
| TASK-NS-02 | Client sets `status = active` directly to fake an active Trip. | Denied — Rules restrict client-writable status to `todo`/`done`; `active` is server-only (`TASK-BR-06`). |
| TASK-NS-03 | Client deletes/edits a task mid-trip via a raw Firestore write to dodge the lock. | Denied — Rules block write when `status == active` (single-doc check; client can't clear `active`). |
| TASK-NS-04 | Client writes malformed/oversized task fields. | Rejected by Security-Rules schema validation (`TASK-RK-01`, `TASK-GAP-06`). |

## 11. Risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| TASK-RK-01 | Client-direct writes move validation into Security Rules, not Functions → malformed data. | Med | Schema validation in Rules (types, length caps); keep the task shape small. |
| TASK-RK-02 | The client-direct precedent gets misapplied to **economy** data later. | High | `TRUST-BR-10` scopes client-direct writes to **non-economy** per-user data only; economy stays Functions-only. |
| TASK-RK-03 | Multi-device last-write-wins → minor edit loss. | Low | Acceptable for tasks; field-level merge where trivial. |
| TASK-RK-04 | Users expect a reward for finishing todos; unrewarded completion feels flat. | Low | Intentional (`TASK-BR-09`); a streak/achievement tie-in could acknowledge it later without paying out. |

## 12. Gaps & Open Questions

| ID | Gap | Current default |
|---|---|---|
| TASK-GAP-01 | Recurring / repeating tasks. | Deferred (post-MVP). |
| TASK-GAP-02 | Subtasks / checklists. | Deferred. |
| TASK-GAP-03 | Priority / tags / custom sorting. | Deferred; default order by `createdAt`/`updatedAt`. |
| TASK-GAP-04 | Reopening a `done` task. | Allowed (`done → todo`, `TASK-BR-08`). |
| TASK-GAP-05 | "Mark done?" convenience prompt on trip completion. | **Not** in MVP (Decision ①, manual). Revisit if users ask. |
| TASK-GAP-06 | Security-Rules schema-validation specifics (field caps, allowed values). | Needs definition before Rules are written. |

## 13. Misalignments & Tensions

| ID | Tension | Resolution |
|---|---|---|
| TASK-MIS-01 | README §0 "the client **never** talks to the database directly" vs Decision ② (client-direct task writes). | **Resolved in favor of README §8's more precise posture** — "*economy fields* not directly writable; per-user docs readable/writable by owner." §0's blanket phrasing is about the **authoritative economy**; non-economy per-user data (tasks) is explicitly client-writable, Security-Rules-gated. Codified as `TRUST-BR-10`. |

## 14. Wellbeing Guardrails

- **No pressure mechanics** on task creation/completion; completing todos is unrewarded (`TASK-BR-09`) so the app
  never nudges users to inflate their list for points.
- Encouraging empty states; the list is a calm on-ramp to focus, not a backlog guilt-trip.

## 15. Acceptance Criteria (Given/When/Then)

```
TASK-AC-01  Many trips, no auto-complete
  Given a task with two completed trips
  When a third trip for it completes
  Then the task is still not 'done' until the user marks it done

TASK-AC-02  Owner-only writes
  Given user B is authenticated
  When B attempts to write user A's task
  Then the write is denied by Security Rules

TASK-AC-03  Client cannot set 'active'
  Given a client-direct task write
  When it attempts to set status = 'active'
  Then the write is rejected (active is server-only)

TASK-AC-04  Estimate pre-fills duration, overridable
  Given a task with estimatedMinutes = 25
  When the user starts a trip from it
  Then the focus duration defaults to 25
  And the user can change it before starting

TASK-AC-05  Locked during active trip
  Given a task whose trip is ACTIVE
  When the user attempts to edit or delete it
  Then the action is blocked with an explanation

TASK-AC-06  Completion grants no currency
  Given any task
  When the user marks it done
  Then no Fare or Gems are granted

TASK-AC-07  Offline create syncs
  Given the device is offline
  When the user creates a task
  Then it appears locally immediately and syncs to Firestore on reconnect

TASK-AC-08  History survives deletion
  Given a task with a completed trip
  When the task is deleted
  Then the trip retains the task-title snapshot taken at start
```
