---
name: page-buddy
description: 'Page buddy live-tamper architecture and development. USE FOR: modifying page_buddy.cljs or page_buddy_sprites.cljs, adding behaviors/animations/effects, refactoring state management, debugging physics or FSM logic. IMPORTANT: Also load when PLANNING page buddy changes. Enforces Uniflow invariants adapted for Epupp/SCI without Replicant.'
---

# Page Buddy

Architecture skill for the page buddy live-tamper — an animated cat that walks, jumps, sits, and reacts to user interactions on web pages. Runs as Scittle (SCI) in the browser via Epupp.

## When to Use This Skill

- Modifying `page_buddy.cljs` or `page_buddy_sprites.cljs`
- Adding new behaviors, animations, or effects
- Refactoring state management or the dispatch loop
- Debugging physics, FSM transitions, or sprite rendering
- Planning page buddy feature work

## Prerequisites

- Load the **uniflow** skill — page buddy implements a Uniflow variant
- Load the **epupp** skill — page buddy runs as a Scittle live-tamper
- Load the **backseat-driver** skill — for REPL-driven development
- Read `page_buddy.cljs` in its entirety, without chunking. Understand the dispatch loop, FSM, and physics model before making changes.

## Architecture: Uniflow Without Replicant

Page buddy adapts Uniflow for direct DOM manipulation in SCI. No Replicant views, no enrichment system. The dispatch loop (`make-dispatch` factory) builds `uf-data` manually from `!env` snapshots and is the sole writer to both atoms.

### Two Atoms, One Writer

| Atom | Scope | Purpose | Writer |
|------|-------|---------|--------|
| `!state` | `defonce` | Application state: buddy-state, position, animation, el/container refs, raf-id | Only `dispatch!` |
| `!env` | `defonce` | Environment: mouse-x, mouse-y, scroll-y, drag data, surfaces, handler refs | Only `dispatch!` |

`!state` is application truth. `!env` is an input sensor — analogous to enrichment data, not managed state. Both are written exclusively by the dispatch loop.

### Dispatch Factory

`dispatch!` is created by `(make-dispatch !state !env)` — a factory that closes over both atoms. This eliminates `(declare dispatch!)` and makes the atoms threadable for testing.

```clojure
(def dispatch! (make-dispatch !state !env))
```

### uf-data Contract

`dispatch!` snapshots `!env` once at batch start and builds `uf-data`:

```clojure
{:system/now          (js/Date.now)
 :roll                (rand)
 :mouse/x             (:mouse-x env)
 :mouse/y             (:mouse-y env)
 :scroll/y            (:scroll-y env)
 :surfaces/visible    (:surfaces env)
 :drag/data           (:drag env)
 :env/drag-handler    (:drag-handler env)
 :env/mouse-handler   (:mouse-handler env)
 :env/click-handler   (:click-handler env)
 :env/scroll-handler  (:scroll-handler env)}
```

Actions read environment data exclusively through `uf-data`. Handler refs appear in `uf-data` so `ax.stop` can pass them to cleanup effects without reading `!env`.

### Action Return Contract

Actions return a map with any combination of these keys, or `nil`:

| Key | Purpose | Consumer |
|-----|---------|----------|
| `:uf/db` | New state map | Dispatch loop → `reset! !state` |
| `:uf/fxs` | Effect vectors to execute | Dispatch loop → `perform-effect!` |
| `:uf/dxs` | Derived actions to dispatch next | Dispatch loop → recursive `dispatch!` |
| `:uf/env` | Env delta map to merge | Dispatch loop → `swap! !env merge` |

### Effect Return Contract

Effects (`perform-effect!`) return `nil` or `{:env {...}}` for env deltas. The dispatch loop merges returned `:env` maps into `!env`. Effects never touch atoms directly.

## Non-Negotiable Invariants

All Uniflow invariants apply. These are the page-buddy-specific reinforcements:

1. **`!state` access**: Only `dispatch!` may `deref` or `reset!` `!state`. Actions receive state as a plain map parameter.
2. **`!env` access**: Only `dispatch!` (the `make-dispatch` loop) may read or write `!env`. Event handler callbacks dispatch `[:buddy/ax.env-merge ...]` actions. Effects return `{:env {...}}` data for the dispatch loop to merge.
3. **Action purity**: `handle-action` receives `(state uf-data action)` → returns `{:uf/db :uf/fxs :uf/dxs :uf/env}` or `nil`. No side effects. No atom access. No `js/` calls except `js/Math` and `js/window.innerWidth`/`innerHeight` for layout geometry.
4. **Effect isolation**: `perform-effect!` receives `[dispatch-fn effect]`. It never reads atoms. It returns `{:env {...}}` for env deltas or `nil`. Async callbacks it installs communicate via `dispatch-fn` (dispatching env-merge actions), never via atom writes.
5. **Entry point discipline**: `start!` and `stop!` dispatch actions. The RAF callback dispatches actions. Event handlers dispatch actions. None of them read `@!state` for decisions or write to `!env` directly.
6. **Geometry reads**: `js/window.innerWidth`, `js/window.innerHeight`, and `floor-y` are layout queries, not state. They may appear in actions (physics needs the ground plane). They are not violations.

### The One Exception

`start!` reads `(:raf-id @!state)` to guard against double-start. This is the single allowed `@!state` read outside `dispatch!` — a lifecycle guard, not a decision.

## Behavior FSM

```
  ┌─────────────────────────────────────────────────────┐
  │                                                     │
  ▼                                                     │
idle ──▶ walking ──▶ idle                               │
  │      running ──▶ idle                               │
  │      sitting ──▶ sleeping ──▶ idle                  │
  │      meowing ──▶ idle                               │
  │      touching ──▶ idle                              │
  │      jumping ──▶ landing/stunned ──▶ idle           │
  │                                                     │
  ├──────────────────────────────────────────────────────┘
  │
  ▼
being-hit ──▶ stunned ──▶ idle    (from any ground state)
```

- **Ground states**: idle, walking, running, sitting, meowing, touching
- **Airborne states**: jumping, falling
- **Reactive states**: being-hit, stunned, landing
- Behavior selection is in `pick-next-behavior` — uses `(:roll uf-data)` for reproducibility
- State transitions happen via `[:buddy/ax.enter-state new-bstate]`

### Adding a New Behavior

1. Add the sprite animation to `page_buddy_sprites.cljs` (see Sprite System below)
2. Add the state case in `enter-state-action` — set duration, animation, initial values
3. Add the tick case in `handle-action :buddy/ax.tick` — what happens each tick
4. Add the transition trigger — from `pick-next-behavior`, from a click reaction, or from a new entry point
5. Map the behavior keyword to its sprite key in `enter-state-action`'s `anim-key` case

## Sprite System

Sprites live in `page_buddy_sprites.cljs`:

- `frame-size`: `{:w 48 :h 32}` — single frame dimensions in source pixels
- `animations`: map of `{:keyword {:frames n :data "data:image/png;base64,..."}}`
- Sprite sheets are horizontal strips: frame 0 at left, frame N at right
- Rendered at 3× scale (configurable via `config :scale`): 144×96px on screen
- Animation uses CSS `background-position` stepping — `anim-fxs` helper generates the effect vectors
- Frame advance is driven by `[:buddy/ax.advance-frame]` action at `:fps` rate

### Re-encoding sprites from source PNGs

Source PNGs are in `~/Downloads/animated-cats.zip`. To re-encode:

```clojure
;; In bb REPL:
(require '[babashka.fs :as fs])
(let [bytes (fs/read-all-bytes "path/to/sprite.png")
      b64 (.encodeToString (java.util.Base64/getEncoder) bytes)]
  (str "data:image/png;base64," b64))
```

Verify with ImageMagick: `magick compare -metric AE source.png decoded.png null: 2>&1` — must be `0`.

## Physics Model

Euler integration in `tick-jumping`:

- Gravity: 0.5 px/tick² (configurable)
- Terminal velocity: 12 px/tick
- Jump impulse: vy = -8
- Ground plane: `(floor-y)` = `window.innerHeight - 40 - (h × scale)`
- Landing at ground: vy > 6 → stunned, else → idle
- Horizontal clamping: 0 to `window.innerWidth - (w × scale)`

## Action Naming

```
:buddy/ax.init           — initialize from DOM refs
:buddy/ax.enter-state    — FSM transition
:buddy/ax.advance-frame  — sprite frame step
:buddy/ax.tick           — physics + behavior tick
:buddy/ax.scan-surfaces  — trigger surface scan effect
:buddy/ax.drag-release   — handle drag release with velocity
:buddy/ax.react-to-click — respond to page click
:buddy/ax.env-merge      — merge key-value pairs into env (used by async callbacks)
:buddy/ax.stop           — teardown (resets env via :uf/env)
:buddy/ax.assoc          — generic state update (raf-id)
```

All actions are namespaced `:buddy/ax.*`. Effects are namespaced by domain: `:dom/fx.*`, `:timer/fx.*`, `:log/fx.*`. Effects that register handlers return `{:env {:handler-name handler-fn}}` — no `:env/fx.*` namespace exists.

## Development Workflow

Page buddy is developed REPL-first via the Epupp default session (`epupp`, port 3339/3340).

### Rich Comment Form at file bottom

```clojure
(comment
  (start!)
  (stop!)
  (dispatch! [[:buddy/ax.enter-state :walking]])
  @!state
  @!env
  :rcf)
```

Evaluate individual forms to test behaviors live. `@!state` and `@!env` in RCF are for interactive inspection only — they are not part of the running system's data flow.

### Test cycle

1. Evaluate the file (loads all defs)
2. `(start!)` — spawns the buddy
3. Test behavior by evaluating dispatch forms
4. `(stop!)` — clean teardown
5. Modify code → re-evaluate changed forms → repeat

### After code changes

Page navigation destroys the SCI runtime. After reload, re-evaluate the file before `(start!)`.
