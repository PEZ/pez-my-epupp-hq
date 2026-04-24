# Page Buddy — Behavior Plan (Phase 4+)

Phases 0–3 are complete and archived in `page_buddy_behavior_plan_phases_0-3.md`.

**Architecture**: Uniflow with `!state` + `!env`, single writer `dispatch!`, pure actions, imperative effects. See [SKILL.md](../.github/skills/page-buddy/SKILL.md).

---

## Phase 4: Scroll-Aware Surface Attachment

The cat currently stores position as absolute viewport coordinates. When the page scrolls, elements move but the cat doesn't — it floats in space until `check-surface-validity` detects the surface left the viewport and the cat falls. This phase makes the cat scroll with the element it's on.

This is foundational infrastructure: the relative positioning model introduced here is the same model that wall and ceiling attachment will use later.

### The Problem

```
User scrolls → element moves → cat stays at old viewport position → visual disconnect
→ check-surface-validity detects "scrolled away" → cat falls
```

### The Solution

When attached to a surface, store position as an **offset along the surface** (`:surface/offset-x`). Each tick, derive viewport coordinates from the element's live `getBoundingClientRect()` + offset. Scrolling "just works" — the element moves, the derived position moves with it.

### New State Key: `:surface/offset-x`

Horizontal distance in pixels from the element's left edge to the cat's left edge. Think "how far along the surface the cat is standing."

| Attachment state | `:surface/offset-x` | `:pos/x`, `:pos/y` | Authority |
|-----------------|---------------------|---------------------|-----------|
| On surface | `number` | Derived from offset + live rect | Offset is authoritative |
| On floor (bottom bar) | `nil` | Direct mutation | Pos is authoritative |
| Airborne (jumping, falling) | `nil` | Physics simulation | Pos is authoritative |
| Dragging | `nil` | Drag position | Pos is authoritative |

**Invariant**: `:pos/x` and `:pos/y` are always valid viewport coordinates — whether direct or derived. This means all existing consumers (hit-testing, click reactions, cursor-chase distance, perch-finding) work without change.

### Slim Surface Map

`:buddy/current-surface` drops stale `:geom/*` keys:

**Before**: `{:dom/el el :geom/top top :geom/left left :geom/right right :geom/bottom bottom :geom/width w :geom/height h}`
**After**: `{:dom/el el}`

All geometry is read live via `getBoundingClientRect` when needed. Verified: no code path reads `:geom/*` from the stored `:buddy/current-surface` — all `:geom/*` reads are on fresh scan results from `:surfaces/visible`.

Note: `:surfaces/visible` in `!env` keeps the full `{:dom/el :geom/*}` shape — these are ephemeral viewport snapshots used by `find-landing-surface` and `find-perch-target` and are valid at scan time.

### Extract Constant Dimensions

The expression `(* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))` appears ~25 times. Extract:

```clojure
(def cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config)))
(def cat-h (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config)))
```

### New Helpers

```clojure
(defn derive-viewport-pos
  "Derive viewport [x y] from surface offset + live rect. Returns nil if element disconnected."
  ([state]
   (derive-viewport-pos (:buddy/current-surface state) (:surface/offset-x state)))
  ([current-surface offset-x]
   (when-let [el (:dom/el current-surface)]
     (when (.-isConnected el)
       (let [rect (.getBoundingClientRect el)]
         [(+ (.-left rect) (or offset-x 0))
          (- (.-top rect) cat-h)])))))

(defn offset-from-viewport
  "Convert viewport x to surface-relative offset."
  [viewport-x surface-el]
  (let [rect (.getBoundingClientRect surface-el)]
    (- viewport-x (.-left rect))))

(defn surface-walk-bounds
  "Offset bounds [min max] for walking on a surface element."
  [surface-el]
  (let [rect (.getBoundingClientRect surface-el)]
    [0 (- (.-width rect) cat-w)]))

(defn on-surface?
  "True when cat is in surface-relative positioning mode."
  [state]
  (some? (:surface/offset-x state)))
```

`getBoundingClientRect` in actions is permitted — existing `tick-walking` already does it, and the SKILL.md invariant 7 allows geometry reads.

### Tick Dispatch Architecture

The main tick dispatch (`:buddy/ax.tick`) gains two orchestration steps:

**1. Pre-tick refresh** (before behavior logic): Derive viewport pos from offset, update `:pos/x`/`:pos/y`. This ensures `check-surface-validity`, distance calculations, and all behavior logic see correct viewport coords.

**2. Post-tick position-fxs** (after behavior result): If attached, unconditionally emit `position-fxs` with the derived coords. This gives all surface-attached states (idle, sitting, sleeping, meowing, touching, stunned, landing, edge-contemplating) scroll-tracking for free — no per-handler position logic needed.

**Optimization**: Read `getBoundingClientRect` once at the start of the tick, pass the rect through. Eliminates 2-3 redundant calls per tick.

```
Energy update → PRE-TICK REFRESH (rect read, pos derivation)
  → Surface validity check → Timeout transitions → Behavior dispatch
  → POST-TICK position-fxs emission
```

### Per-Handler Changes

| Handler | Current | New |
|---------|---------|-----|
| `tick-walking` (on surface) | Updates `:pos/x`, clamps to live rect bounds, emits position-fxs | Updates `:surface/offset-x`, clamps to `[0, el-width - cat-w]`, does NOT emit position-fxs |
| `tick-walking` (on floor) | Unchanged | Unchanged — viewport coords, emits position-fxs directly |
| `tick-idle`, `tick-edge-contemplating` | No position update | No position update — pre/post tick handles it |
| Sitting, sleeping, meowing, touching, stunned, landing | No position update | No position update — pre/post tick handles it |
| `tick-jumping` | Airborne physics, finds landing | Landing converts viewport x → offset via `offset-from-viewport`. Uses live rect (not scan `:geom/top`) for landing y |
| `tick-cursor-chasing` | Floor-only, viewport coords | Unchanged. `enter-state-action` clears surface/offset (cat drops to floor to chase) |
| `tick-dragging` | Already clears surface | Also clears `:surface/offset-x` |

### Attach / Detach Protocol

**Attach** (landing on surface, in `tick-jumping`):
```
rect = el.getBoundingClientRect()
offset-x = cat-viewport-x - rect.left
Store: {:buddy/current-surface {:dom/el el}
        :surface/offset-x offset-x
        :pos/x viewport-x  :pos/y (- rect.top cat-h)
        :vel/x 0 :vel/y 0}
```

**Detach** (jump, drag, being-hit, surface-lost):
```
pos/x and pos/y are already valid viewport coords (derived on last tick)
Clear: {:buddy/current-surface nil :surface/offset-x nil}
```

**Between-tick detach** (e.g., being-hit from scroll handler): Must read a fresh rect before clearing, because pos may be stale from the previous tick.

### Scroll Handler Refactor

Move business logic out of the effect and into a pure action:

**Effect** dispatches raw data:
```clojure
(dispatch-fn [[:buddy/ax.on-scroll curr-y (js/Math.abs dy)]])
```

**Action** decides based on attachment:
```clojure
:buddy/ax.on-scroll
;; Attached cats ignore large-scroll hits — they're riding the element
;; Unattached cats get being-hit on large jumps (existing behavior)
(let [[scroll-y abs-dy] args]
  (cond-> {:uf/env {:scroll/y scroll-y}}
    (and (> abs-dy 500) (not (on-surface? state)))
    (assoc :uf/dxs [[:buddy/ax.enter-state :bs/being-hit]])))
```

### Being-Hit While Attached

Being-hit always detaches — a hit knocks the cat off the surface. Since scroll-based being-hit is suppressed for attached cats, only user-initiated hits remain (clicks). These should feel impactful.

Post-being-hit: stunned timer expires → check if on solid ground. If mid-air (no surface, above floor), transition to `:bs/falling` instead of `:bs/idle`.

### `check-surface-validity` Changes

Signature: takes the live rect (threaded from the pre-tick read) instead of reading DOM itself.

Threshold: Keep current (fully off-screen). With relative positioning, the cat rides the element — it goes off-screen when the element does. The element bottom leaves the viewport before the cat does (cat sits on top), providing natural margin. Add explicit margin later if users report aggressive detach.

### Detach Sites (Mechanical Change)

Every place that currently does `(assoc state :buddy/current-surface nil)` gains `:surface/offset-x nil`:

- `enter-state-action :bs/dragging`
- `enter-state-action :bs/being-hit` (new)
- `enter-state-action :bs/cursor-chasing`
- `tick-edge-contemplating` (jump-off branch)
- `tick-dragging` (break-free)
- `drag-release` action
- Surface-lost handler in tick dispatch
- Floor landing in `tick-jumping`

### Scroll Jitter: Follow-up Optimization

At 10Hz tick rate, a fast scroll produces visible lag — the element moves at 60fps but the cat updates at 10Hz. A visual-only `requestAnimationFrame` position update in the scroll handler (direct DOM write, no state mutation) would eliminate this. Not a blocker — note as a follow-up refinement.

### Migration Steps

Each step is independently testable and leaves the system shippable:

1. **Infrastructure**: Add `cat-w`/`cat-h` constants, helpers (`derive-viewport-pos`, `offset-from-viewport`, `surface-walk-bounds`, `on-surface?`), add `:surface/offset-x nil` to `init-action`. No behavior changes.

2. **Atomic offset system**: Landing stores offset, `tick-walking` uses offset (surface branch), pre-tick refresh derives viewport pos, post-tick emits position-fxs. Slim surface map to `{:dom/el el}`. This is one atomic step — offset-store, offset-walk, and viewport-refresh are a semantic unit that can't be shipped separately.

3. **Scroll handler → action**: Replace conditional being-hit in effect with `:buddy/ax.on-scroll` action. Attached cats ignore large-scroll hits.

4. **Detach cleanup**: Add `:surface/offset-x nil` to all detach sites. Add being-hit detach. Add stunned→falling for mid-air cats.

5. **Verification**: Walk all FSM transitions in REPL. Verify floor behavior unchanged. Verify attached scroll behavior. Verify detach from each trigger.

### Edge Cases

| Situation | Handling |
|-----------|----------|
| Surface scrolled partially off | Cat stays — rides the element. Detach only when fully off-screen |
| Surface element removed from DOM | `check-surface-validity` → `:surface/removed` → fall |
| Element resize (accordion, dynamic content) | Walk bounds recalculated from live rect each tick. Offset clamped. |
| Window resize | Rect changes, viewport pos recalculated on next tick |
| Horizontal scroll | Same as vertical — `getBoundingClientRect` is viewport-relative |
| Click on attached cat (between ticks) | `:pos/x`/`:pos/y` may be up to 100ms stale. Minor — click distance check tolerates this |
| Cursor-chase triggers while on surface | Cat detaches to chase on floor. Natural cat behavior. |

---

## Phase 5: Multi-Surface Movement

Phase 5 introduces walls, ceilings, corners, and the primitives to transition between them. The cat graduates from a floor-walker to a spatially free companion. This builds on Phase 4's relative positioning model — wall and ceiling attachment use the same offset-from-element pattern.

**Unused sprites activated this phase:**
- `:anim/climb` (7 frames) — wall climbing
- `:anim/climb-idle` (4 frames) — clinging to wall, stationary

### Surface Types

| Surface | Physical meaning | CSS transform (inner element) |
|---------|-----------------|-------------------------------|
| **Floor** | Bottom bar or element top edge | none (+ `scaleX(-1)` for facing-left) |
| **Left wall** | Left edge of tall element | `rotate(-90deg)` |
| **Right wall** | Right edge of tall element | `rotate(90deg)` |
| **Ceiling** | Bottom edge of element above | `scaleY(-1)` |

Surface type is **derived**, not stored — a pure function of `:buddy/current-surface` geometry + cat position determines which face the cat is on.

## Movement Primitive Catalog

### Floor Primitives (exist)

| Primitive | Sprite | Status |
|-----------|--------|--------|
| walk-horizontal | `:anim/walk` | Implemented |
| run-horizontal | `:anim/run` | Implemented |
| jump-arc | `:anim/jump` | Implemented |
| fall | `:anim/jump` | Implemented |
| platform-hop | `:anim/jump` | Implemented (perching) |

### Wall Primitives (Phase 5a)

| Primitive | Description | Sprite | Cat-likeness |
|-----------|-------------|--------|--------------|
| `wall-scramble` | Sprint at wall, redirect upward. Initial speed = run-speed, decays. | `:anim/climb` | ★★★★★ |
| `wall-mount` | From standing, reach up and pull onto wall. Slower, deliberate. | `:anim/climb` (half frame rate) | ★★★★★ |
| `wall-walk` | Move along wall surface (up or down) at constant climb speed. | `:anim/climb` | ★★★★★ |
| `wall-idle` | Cling to wall, stationary. | `:anim/climb-idle` | ★★★★★ |
| `wall-slide` | Lose grip, slow descent. Constant slide speed, decelerating. | `:anim/climb` (slow) | ★★★★★ |
| `wall-jump` | Push off wall into parabolic arc away from surface. | `:anim/jump` | ★★★★ |
| `wall-backflip` | Sprint up wall, decelerate to zero, push off backward in arc. | `:anim/climb` → `:anim/jump` | ★★★★★ |
| `wall-drop` | Release grip, small horizontal kick away, gravity resumes. | `:anim/jump` | ★★★★ |
| `climb-down` | Reverse climb, controlled descent. Cats go down backward. | `:anim/climb` (reversed facing) | ★★★★★ |

### Corner Primitives (Phase 5c)

| Primitive | Description | Sprite | Cat-likeness |
|-----------|-------------|--------|--------------|
| `corner-floor-to-wall` | At element edge, brief pause, sprite rotates, resume on wall. | pause → `:anim/climb` | ★★★★ |
| `corner-wall-to-floor` | At wall base, brief pause, sprite derotates, resume on floor. | pause → `:anim/walk` | ★★★★ |
| `corner-wall-to-ceiling` | At wall top, pivot onto element underside. | pause → `:anim/walk` (flipped) | ★★★ |
| `corner-ceiling-to-wall` | At ceiling edge, pivot down onto wall. | pause → `:anim/climb` | ★★★ |

### Ceiling Primitives (Phase 5c)

| Primitive | Description | Sprite | Cat-likeness |
|-----------|-------------|--------|--------------|
| `ceiling-walk` | Walk upside-down along element bottom edge. | `:anim/walk` + `scaleY(-1)` | ★★ |
| `ceiling-idle` | Hang from ceiling, stationary. | `:anim/climb-idle` + `scaleY(-1)` | ★★ |
| `ceiling-drop` | Release, fall. Mid-air righting reflex (flip orientation). | `:anim/jump` | ★★★★ |

### Airborne Transition Primitives (Phase 5a–5b)

| Primitive | Description | Sprite | Cat-likeness |
|-----------|-------------|--------|--------------|
| `jump-to-wall` | Jump at wall, stick on contact. | `:anim/jump` → `:anim/climb` | ★★★★ |
| `wall-to-wall-jump` | Jump from one wall to opposite wall across gap. | `:anim/jump` | ★★★★★ |
| `leap-to-ceiling` | Strong vertical jump, grab element bottom. | `:anim/jump` → ceiling-idle | ★★ |

### Speed Variants

Speed is a continuous parameter, not discrete modes. Base principle: `effective-speed = base-speed × energy × context-multiplier`.

| Context | Multiplier | Notes |
|---------|-----------|-------|
| Normal climb | 0.75 × walk-speed | Deliberate |
| Panicked scramble | 1.5 × run-speed | After startle near wall |
| Wall-run (momentum) | Starts at run-speed, decays per tick | For wall-backflip |
| Ceiling traverse | 0.5 × walk-speed | Awkward position |
| Lazy hop | 0.7 × jump-vy | Low energy |
| Power leap | 1.3 × jump-vy | High energy |
| Cautious approach | 0.5 × walk-speed | Stalking/hunting |

## Physics Model

### Gravity: No Gravity While Attached

While on any surface (floor, wall, ceiling), gravity is suppressed. Movement is constant-speed along the surface axis. This matches the existing floor pattern — `tick-walking` doesn't fight gravity, it just moves horizontally.

When detaching (jump off, surface lost, drag), gravity resumes from `vy = 0` in world-down direction, regardless of previous surface.

**Wall sliding** is decorative: a behavior state (`:bs/wall-sliding`) with constant downward speed, not physics-driven gravity.

**Wall-run deceleration** is also a behavior property: `wall-scramble` starts at run-speed and decelerates by a fixed amount per tick until speed reaches zero → triggers wall-backflip or wall-slide.

### Coordinate System

Physics stays in **screen space** always (x = horizontal, y = vertical, origin = top-left of viewport).

- **Floor**: `x` changes with walk speed, `y` fixed to surface top
- **Wall**: `y` changes with climb speed, `x` fixed to wall edge
- **Ceiling**: `x` changes with walk speed, `y` fixed to element bottom minus cat height

**Collision bounds**: When on a wall, the cat's visual rectangle is rotated (144×96 → 96×144 on screen). A localized `collision-rect` function handles the swap — not threaded through every function. Only `find-landing-surface`, `check-surface-validity`, and edge clamping need it.

### Position Relative to Surface

Wall-attached and ceiling-attached cats track position **relative to the element** (offset from element top-left), not absolute viewport coordinates. On scroll, the cat moves with the element. The existing `translate3d` positioning is re-computed from element rect + offset each tick.

This is important because `check-surface-validity` already detects scrolled-away surfaces. But a cat on a visible wall that scrolls *partially* should scroll with it, not detach.

## CSS Transform Architecture

### Problem: Class Toggles Don't Compose

Currently `facing-fxs` toggles the `.facing-left` CSS class which sets `transform: scaleX(-1)`. Wall/ceiling states also need transform values. Multiple CSS classes setting `transform` clobber each other.

### Solution: Computed Transform String

Replace class-based transforms with a single inline `transform` style computed from a lookup:

```
(surface-type, facing) → transform string
```

| Surface | Facing Right | Facing Left |
|---------|-------------|-------------|
| Floor | `""` (none) | `"scaleX(-1)"` |
| Left wall | `"rotate(-90deg)"` | `"rotate(-90deg) scaleX(-1)"` |
| Right wall | `"rotate(90deg)"` | `"rotate(90deg) scaleX(-1)"` |
| Ceiling | `"scaleY(-1)"` | `"scaleY(-1) scaleX(-1)"` |

CSS transforms apply right-to-left: `rotate(90deg) scaleX(-1)` means "first flip horizontally, then rotate." This gives the correct visual for a wall-climbing cat facing downward.

The `facing-fxs` helper evolves into an `orientation-fxs` helper that sets the inline transform string directly. The `.facing-left` CSS class is retired.

### Rotation Pivot Offset

The sprite is 48×32 source pixels, rendered at 3× scale = 144×96. After 90° rotation, the visual bounding box becomes 96×144. The center stays put (default `transform-origin: center`), but the top-left corner shifts by `(144 - 96) / 2 = 24px`. The container position must compensate by this offset when on a wall.

`background-position` for sprite frame selection is unaffected by CSS rotation — the background rotates with the element, so frame stepping still works correctly.

## New Config Keys

Derive most speeds from existing values. Only 3 new config keys:

```clojure
{:cfg/climb-speed-ratio  0.75   ;; wall climb = walk-speed × this
 :cfg/ceiling-speed-ratio 0.5   ;; ceiling walk = walk-speed × this
 :cfg/wall-slide-speed   0.5}   ;; px/tick passive wall slide
```

All other speeds derived:
- `climb-speed` = `walk-speed × climb-speed-ratio` = 1.5
- `ceiling-speed` = `walk-speed × ceiling-speed-ratio` = 1.0
- `wall-run-speed` = `run-speed` (with per-tick deceleration)
- `wall-jump-vx` = `walk-speed × 1.5` = 3
- `wall-jump-vy` = `jump-vy × 0.625` = -5
- Corner pause = 300ms (constant, no config needed)

## New FSM States

| State | Description | Tick handler | Energy rate |
|-------|-------------|-------------|-------------|
| `:bs/climbing` | Moving along wall | `tick-climbing` | -0.003 |
| `:bs/climb-idle` | Clinging to wall, stationary | `tick-climb-idle` | 0.001 |
| `:bs/wall-sliding` | Passive descent on wall | `tick-wall-sliding` | -0.001 |
| `:bs/corner-transition` | Brief pause at surface change | (timeout → next state) | -0.002 |
| `:bs/ceiling-walking` | Traversing ceiling | `tick-ceiling-walking` | -0.003 |
| `:bs/ceiling-idle` | Hanging from ceiling | (timeout → `:bs/ceiling-drop`) | 0.001 |

### Timeout Transitions

Add to `timeout-transitions`:
- `:bs/corner-transition` → derived from `:buddy/transition-target` (the surface being transitioned to)
- `:bs/ceiling-idle` → `:bs/falling` (after timeout, cat drops)

**Not** in `timeout-transitions`:
- `:bs/climb-idle` — needs its own tick handler with wall-appropriate behavior selection (resume climbing, change direction, wall-jump, detach)
- `:bs/wall-sliding` — ends on position (reaching wall bottom), not timer

### State Keys

```clojure
{:buddy/climb-direction   ;; :climb/up or :climb/down
 :buddy/transition-target ;; surface map for corner transition target (ephemeral, cleared after transition)
 :buddy/surface-offset    ;; position along surface (1D offset from element edge), for wall/ceiling attachment
 :buddy/scene-phase}      ;; sub-state for multi-step scenes (Phase 5b)
```

`:buddy/surface-type` is **derived**, not stored. A pure function computes it from `:buddy/current-surface` + cat position.

## Scene Catalog

### Phase 5a Scenes — Wall Fundamentals

#### The Proud Climber
Cat walks to a tall element → scrambles up the side → reaches top → corner transition to element top → sits → meows.

**FSM**: `:bs/walking` → `:bs/climbing` → `:bs/corner-transition` → `:bs/idle` → `:bs/sitting`/`:bs/meowing`
**Trigger**: Energy > 0.5, tall element (height > 200px) within reach
**Infrastructure**: wall-surface-detection, climb state, corner-transition

#### The Lazy Wall Slide
Cat clings to wall (climb-idle) → energy low → begins sliding down → reaches bottom → detaches → sits → grooms (touch).

**FSM**: `:bs/climb-idle` → `:bs/wall-sliding` → `:bs/falling` (brief) → `:bs/sitting` → `:bs/touching`
**Trigger**: Energy < 0.4 while on wall
**Infrastructure**: wall-slide state

#### Startle and Flee Up
Click/scroll startles cat → instead of stunned, bolts to nearest wall → panicked scramble up → clings at safe height.

**FSM**: `:bs/being-hit` → `:bs/running` (toward wall) → `:bs/climbing` (1.5× speed) → `:bs/climb-idle`
**Trigger**: Startle event + wall within 300px + energy > 0.5
**Infrastructure**: wall-seek pathfinding

#### Wall Backflip
Cat sprints at wall → runs up with decaying speed → momentum hits zero → pushes off backward → parabolic arc → lands on floor.

**FSM**: `:bs/running` → `:bs/climbing` (decelerating) → `:bs/jumping` (reversed facing, backward arc) → `:bs/landing`/`:bs/stunned`
**Trigger**: High energy (> 0.7), playful personality
**Infrastructure**: deceleration model in climb tick

#### Cat Confused at Wall
Cat walks to element edge → can't climb (element too short) → idle facing wall → turns around → walks away.

**FSM**: `:bs/walking` → `:bs/idle` (facing wall, brief) → `:bs/walking` (reversed)
**Trigger**: Walks into element side that's too short to climb
**Infrastructure**: wall-height eligibility check

### Phase 5b Scenes — Multi-Step & Side Collision

#### Box Explorer
Cat lands on new element → walks to right edge → contemplates → walks back to left edge → contemplates → walks to center → sits. "Mapping" behavior.

**FSM**: `:bs/walking` → `:bs/edge-contemplating` → `:bs/walking` → `:bs/edge-contemplating` → `:bs/walking` → `:bs/sitting`
**Pattern**: Goal + sub-state. Outer state `:bs/scene-box-explorer`, inner phases `:walk-right`, `:contemplate-right`, `:walk-left`, `:contemplate-left`, `:settle`.
**Trigger**: Land on previously unvisited wide element (> 200px)

#### Throw-Cling Recovery
User throws cat → arc passes element side → cat CLINGS to wall mid-flight → scrambles up → sits → meows (indignant).

**FSM**: `:bs/jumping` (thrown) → `:bs/climbing` (wall-cling) → `:bs/climb-idle` → `:bs/climbing` (up) → `:bs/sitting` → `:bs/meowing`
**Infrastructure**: side-collision detection in `tick-jumping` (check left/right edges, not just top)

#### Failed Jump
Cat aims for perch → jump is underpowered (random 15% chance, higher when tired) → falls short → lands → stunned → grooms.

**FSM**: `:bs/perching` → `:bs/jumping` (0.7× computed velocity) → `:bs/falling` → `:bs/stunned` → `:bs/touching`
**Infrastructure**: Trivial — scale `compute-jump-to-surface` output by 0.7 on a random roll

#### Peek-a-boo Edge Hang
Cat at element edge → instead of turning, goes OVER → hangs by front paws (sprite offset below edge) → pulls up or drops.

**FSM**: `:bs/edge-contemplating` → `:bs/edge-hanging` → `:bs/climbing` (pull-up) or `:bs/falling`
**Trigger**: 30% chance at edge instead of turning around
**Infrastructure**: sprite y-offset positioning

#### Post-Landing Groom
After any landing: 40% chance of brief pause → touch animation. "I meant to do that." Higher chance (70%) after hard landing.

**Implementation**: Transition embellishment in `enter-state-action` — not a standalone scene. After `:bs/landing` or `:bs/stunned`, queue a `:bs/touching` follow-up.

#### Cat Hunting
Cat spots an element (image, button) → slow cautious approach (0.5× speed) → brief crouch (sit, 500ms) → pounce (jump toward element) → touch on arrival.

**FSM**: `:bs/walking` (slow) → `:bs/sitting` (brief) → `:bs/jumping` → `:bs/touching`
**Trigger**: Curiosity > 0.6, target element within 400px

### Phase 5c Scenes — Full Spatial Freedom

#### Ceiling Traverse
Cat on element top → jumps up to element above → grabs ceiling (bottom edge) → walks upside-down → reaches edge → drops → lands below.

**FSM**: `:bs/jumping` → `:bs/ceiling-walking` → `:bs/falling` → landing
**Infrastructure**: ceiling-surface-detection, inverted sprite, position relative to element bottom

#### Full Perimeter Walk
Cat walks element top → corner-turn → climbs down side → corner-turn → walks floor at element base → corner-turn → climbs up other side → corner-turn → back on top.

**FSM**: Four `corner-transition` states connecting walk and climb segments. The capstone scene.
**Infrastructure**: All corner primitives, bidirectional wall movement

#### Nap on Warm Spot
Tired cat seeks a previously visited or dark element → walks there deliberately (0.7× speed) → sits → meows (yawn) → extended sleep → wakes → zoomies.

**FSM**: `:bs/walking` (targeted) → `:bs/sitting` → `:bs/meowing` → `:bs/sleeping` (2× duration) → `:bs/idle` → `:bs/running`
**Infrastructure**: memory model (favorite-spot, visited-spots)

## Multi-Step Intent Architecture

Scenes with 3+ steps use a **goal + sub-state** pattern that fits Uniflow:

```clojure
;; In state:
{:buddy/state :bs/scene-box-explorer
 :buddy/scene-phase :scene.box-explorer/walk-to-right-edge
 :buddy/scene-data {:target-surface {...}}}
```

The tick handler for the scene state dispatches on `:buddy/scene-phase`. Phase transitions are `assoc`s on state — no queue, no planner. The outer FSM sees one state; the inner phases are data.

**Interrupts** (drag, surface-lost, click) are handled at the outer level — same as today — and automatically cancel the scene by transitioning to `:bs/dragging`, `:bs/falling`, etc.

**Behavior selection**: `pick-next-behavior` returns scene states with the same probability weighting as any other behavior. The scene phases are private to that scene's tick function.

## Infrastructure Unlock Dependencies

```
wall-surface-detection ──► Proud Climber
                        ├── Lazy Wall Slide
                        ├── Startle and Flee Up
                        ├── Wall Backflip
                        └── Cat Confused at Wall

side-collision-detection ──► Throw-Cling Recovery
                          └── (future: Hallway Bounce)

ceiling-surface-detection ──► Ceiling Traverse

corner-transition ──► Full Perimeter Walk

multi-step-intent ──► Box Explorer
                   ├── Cat Hunting
                   └── Nap on Warm Spot

computed-transform-string ──► ALL wall/ceiling scenes
```

**Highest-value infrastructure investment**: wall-surface-detection + computed transform strings. These unlock 5 scenes directly.

## Surface Detection Extension

Current `scan-surfaces-data` finds horizontal perching surfaces (element top edges). Phase 4 extends this to detect:

- **Wall surfaces**: element left/right edges where `height > 200px`
- **Ceiling surfaces**: element bottom edges reachable from below

The same `querySelectorAll` + `getBoundingClientRect` scan, with additional geometry analysis per element. Each element can contribute multiple surfaces:

```clojure
{:dom/el el
 :geom/top top :geom/left left :geom/right right :geom/bottom bottom
 :geom/width w :geom/height h
 :surface/faces #{:surface/floor :surface/left-wall :surface/right-wall}}
```

**Visual container gating** (for future interior behaviors): check `getComputedStyle` for visible borders/backgrounds before treating an element as an "interior." A borderless, transparent `div` is not a box.

## Edge Cases

| Situation | Handling |
|-----------|----------|
| Click on wall-attached cat | `:bs/being-hit` → detach with small impulse, gravity resumes |
| Scroll while on wall | Cat moves with element (relative positioning). If element leaves viewport → `:bs/falling` |
| Drag from wall | Clear surface attachment, clear orientation transform, standard drag behavior |
| Ceiling edge reached | Edge-contemplation equivalent → drop or corner-turn to wall |
| Wall element removed | `check-surface-validity` detects `.isConnected = false` → `:bs/falling` |
| Wall too short to climb | Abort climb attempt, stay on floor (Cat Confused scene) |
| Thrown at wall too fast | Being-hit on contact → wall-slide (high velocity = can't grip) |

## Implementation Phases

### Phase 5a: Wall Fundamentals
**Theme**: Cat uses vertical surfaces.

Infrastructure:
- Wall-surface-detection in `scan-surfaces-data`
- Computed transform string (retire `.facing-left` class toggle)
- Rotation pivot offset compensation (24px)
- `:bs/climbing`, `:bs/climb-idle`, `:bs/wall-sliding` states
- `tick-climbing`, `tick-climb-idle`, `tick-wall-sliding` handlers
- `energy-rates` entries for new states
- `collision-rect` helper for rotated bounds

Scenes: Proud Climber, Lazy Wall Slide, Startle and Flee Up, Wall Backflip, Cat Confused at Wall

**Shippable result**: Cat climbs walls, clings, slides down, does backflips. The unused climb sprites finally activate.

### Phase 5b: Multi-Step Scenes + Side Collision
**Theme**: Cat has complex behaviors and reacts to throws.

Infrastructure:
- Side-collision detection in `tick-jumping` (left/right edge intercept)
- Multi-step intent pattern (goal + sub-state)
- Post-landing groom embellishment

Scenes: Box Explorer, Throw-Cling Recovery, Failed Jump, Peek-a-boo Edge Hang, Post-Landing Groom, Cat Hunting

**Shippable result**: Cat tells multi-step stories, clings to walls when thrown, fails jumps hilariously.

### Phase 5c: Full Spatial Freedom
**Theme**: Cat owns all surfaces.

Infrastructure:
- Ceiling-surface-detection
- Corner-transition primitives (all four directions)
- Position-relative-to-element for ceiling attachment
- Memory model (visited-spots, favorite-spot, favorite-perch)

Scenes: Ceiling Traverse, Full Perimeter Walk, Nap on Warm Spot

**Shippable result**: Cat circumnavigates elements, walks upside-down on ceilings, has a favorite sleeping spot.

### Phase 6: Polish + Advanced (future)
- Personality traits (adventurous, playful, sociable)
- Curiosity + comfort needs
- MutationObserver reactions
- Luminance detection for "warm spots"
- Interior container behavior (with visual-container gating)
- Hallway bounce (wall-to-wall in narrow gaps)
- Touch device support

## Sprite Coverage

**Zero new sprite art needed for Phase 4.** All 12 existing animations cover every scene through CSS transforms:

| Animation | Floor | Wall | Ceiling |
|-----------|-------|------|---------|
| `:anim/walk` (3f) | walk | — | ceiling-walk (`scaleY(-1)`) |
| `:anim/run` (5f) | run | — | — |
| `:anim/climb` (7f) | — | climb/slide | — |
| `:anim/climb-idle` (4f) | — | wall-idle | ceiling-idle (`scaleY(-1)`) |
| `:anim/jump` (8f) | jump/fall | wall-jump launch | ceiling-drop |
| `:anim/idle` (8f) | idle | — | — |
| `:anim/sit` (10f) | sit | — | — |
| `:anim/sleep` (12f) | sleep | — | — |
| `:anim/meow` (3f) | meow | — | — |
| `:anim/touch` (3f) | touch/groom | — | — |
| `:anim/being-hit` (3f) | hit reaction | — | — |
| `:anim/stunned` (6f) | stunned/landing | — | — |

## Performance Notes

- Rotation transforms are GPU-composited (same perf cost as existing `scaleX(-1)`)
- Surface scanning adds wall/ceiling face analysis — same `getBoundingClientRect` call, slightly more processing, still well within 0.5ms budget
- Relative positioning requires re-computing container position from element rect each tick — one `getBoundingClientRect` per tick while wall/ceiling-attached. Acceptable at 100ms tick interval.

---

## Original Plan-producing Prompt

> **Phase 4 (Scroll-Awareness)**: The cat doesn't scroll with the page when on a floor. Often it is "on" surfaces and they move when the page scrolls. Adapting for scroll-awareness is foundational — the relative positioning model introduced here is the same model wall/ceiling attachment will use. Should come before new primitives.
>
> Three parallel Epupp Assistant subagents planned (state model, tick handlers, effects/transitions), then three parallel reviewers (Epupp Assistant, Clojure, clojure-reviewer) cross-reviewed. Synthesized with reconciled naming (`:surface/offset-x`), merged migration steps (atomic offset system), and resolved pre-tick vs post-tick refresh timing.
>
> Key refinements from review process:
> - Key name: `:surface/offset-x` in `:surface/` namespace, not `:buddy/` (Epupp reviewer)
> - Pre-tick refresh for pos + post-tick for position-fxs (Epupp reviewer)
> - Between-tick detach must read fresh rect (Clojure reviewer)
> - Migration steps 2-4 merged as atomic (Clojure reviewer — offset-store + offset-walk + viewport-refresh are semantic unit)
> - Thread one rect through per tick to reduce redundant calls (Epupp reviewer)
> - Scroll jitter: visual-only rAF update as follow-up (data flow reviewer)
> - Extract `cat-w`/`cat-h` as constants — ~25 duplicate expressions (data flow reviewer)
> - Slim surface map verified safe — no `:geom/*` reads from stored surface (Clojure reviewer)
>
> **Phase 5 (Multi-Surface Movement)**: Walls, ceilings, corners, movement primitives. Split into 5a/5b/5c.
>
> Three parallel analyzers (surface transitions, scene sequences, physics/sprites), then three parallel reviewers (Epupp Assistant, Clojure, Clojure Reviewer) to critique. Synthesized.
>
> Key refinements from review process:
> - Surface type derived, not stored (Clojure reviewer)
> - CSS transform: computed inline string, not class toggles (all reviewers flagged)
> - Config: derive from ratios, 3 keys not 10 (Clojure reviewer)
> - Corner transitions: single `:buddy/transition-target`, not from/to pair (Clojure reviewer)
> - Multi-step intent: goal + sub-state, not queue (scene reviewer)
> - Wall backflip added as missing primitive (transition reviewer)
> - Energy rates mandatory for all new states (Clojure reviewer)
> - Position relative to element for wall/ceiling attachment (scene reviewer)
> - Wall-run deceleration as foundational physics, not separate state (transition reviewer)
