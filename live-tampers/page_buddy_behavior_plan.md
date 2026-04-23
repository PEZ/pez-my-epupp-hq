# Page Buddy Behavior Plan

A plan for evolving the page buddy cat from a simple bottom-bar walker into a DOM-aware, personality-driven companion that creates emergent micro-stories on any web page.

## Current State

The cat has 12 sprite animations, 6 of which are in use:

| Animation | Frames | Status |
|-----------|--------|--------|
| walk | 3 | In use |
| idle | 8 | In use |
| run | 5 | **Unused** |
| sit | 10 | In use |
| sleep | 12 | In use |
| jump | 8 | **Unused** |
| meow | 3 | In use |
| climb | 7 | **Unused** |
| climb-idle | 4 | **Unused** |
| touch | 3 | In use |
| being-hit | 3 | **Unused** |
| stunned | 6 | **Unused** |

Architecture: Uniflow (pure actions → effects → single dispatch). Container: `position: fixed; bottom: 40px`. Positioning via `left` CSS property. Two `setInterval` timers (8fps sprite, 100ms state tick).

## Prior Art

| Project | Key Pattern | Takeaway |
|---------|------------|----------|
| **Shimeji** (Java) | Window edges as physical platforms, BorderType system (floor/wall/ceiling), behavior-tree XML | Treat DOM elements as surfaces. Gravity when surface disappears. |
| **eSheep** (Windows) | Walk on title bars, fall between windows, parachute | Gravity simulation is essential for believability |
| **Neko** (X11) | Chase mouse cursor, sleep when idle | Cursor reactivity = maximum personality for minimum code |
| **DyberPet** (Python) | Euler gravity (`vy += gravity`), drag-release velocity | Simple physics model that works |
| **Tamagotchi** | Hidden needs meters drive behavior | Users infer personality from simple meter interactions |
| **The Sims** | Personality modulates *strategy*, not *goals* | Traits as multipliers on behavior weights |
| **Dwarf Fortress** | `personality × memory × needs → behavior_weights` | Simple rules → emergent stories |
| **Bonzi Buddy** | Intrusive, interruptive, adware | **Never** block page interaction. Ambient > interruptive. |

**Core lesson**: Reactivity to user input + behavioral rhythm + consistency = perceived personality. Users anthropomorphize the rest.

## Architecture Decisions

### Coordinate System

Switch from `left`/`bottom` CSS to `{x, y}` viewport-relative coordinates rendered via `transform: translate3d(x, y, 0)`. This enables vertical movement (jumping, falling, climbing) and GPU-composited rendering.

### Unified Tick Loop

Replace the current dual-`setInterval` with a single `requestAnimationFrame` loop:

1. Accumulate elapsed time via delta
2. If physics threshold crossed → run physics substep (pure)
3. If state threshold crossed → run state/mood update (pure)
4. If sprite threshold crossed → advance frame
5. Single `dispatch!` with all accumulated actions

This eliminates timer drift and read-modify-write races between competing intervals.

### DOM Queries as Effects

DOM reads (`getBoundingClientRect`, `getComputedStyle`) are impure. Route them through the effect layer:

```
action decides to explore
  → effect queries DOM
  → dispatch result as new action
  → pure handler processes data
```

Cache surface data, re-query only every ~5-10s or on scroll/resize events.

### Layered Decision Architecture

Separate behavior decisions into layers:

1. **Physics layer**: Determines which transitions are physically possible (can't walk if airborne, can't climb without adjacent surface). Output: set of allowed transitions.
2. **Behavior layer**: From the allowed set, selects which transition to take using mood weights + personality.
3. **Animation layer**: Maps state → sprite. Always a pure lookup.

### Event Listener Lifecycle

Every DOM event listener (scroll, click, mousemove) must be tracked in state and removed in `stop!`. Add listener tracking infrastructure before adding any listeners.

## Animation State Machine

```
State           │ Animation    │ Movement
────────────────┼──────────────┼──────────────────
:idle           │ :idle        │ none
:walking        │ :walk        │ horizontal, walk-speed
:running        │ :run         │ horizontal, run-speed
:sitting        │ :sit         │ none
:sleeping       │ :sleep       │ none
:meowing        │ :meow        │ none
:touching       │ :touch       │ none
:jumping        │ :jump        │ parabolic arc
:falling        │ :jump (5-8)  │ vertical down (gravity)
:climbing       │ :climb       │ vertical up
:climbing-idle  │ :climb-idle  │ none (on wall)
:landing        │ :stunned     │ none (brief)
:being-hit      │ :being-hit   │ knockback
:stunned        │ :stunned     │ none
```

### Transition Graph

```
                    ┌──────────┐
            ┌───────│  :idle   │◄──────────────┐
            │       └─┬──┬──┬─┘                │
            │         │  │  │                   │
            ▼         ▼  │  ▼                   │
       :walking  :sitting │ :meowing        :landing
         │  │      │      │    │               ▲
         │  │      ▼      │    │               │
         │  │  :sleeping  │    │            :falling
         │  │             │    │               ▲
         │  └─────────────┼────┘               │
         │                │                    │
         ▼                ▼                    │
    :climbing ◄──── (near wall)          :jumping
         │                                     ▲
         ▼                                     │
   :climbing-idle                         (from any
         │                                 surface)
         ▼
    :jumping (off wall)
```

### Transition Triggers

| From | To | Trigger |
|---|---|---|
| `:idle` | `:walking` | timeout + behavior weight roll |
| `:idle` | `:sitting` | behavior weight roll |
| `:idle` | `:meowing` | behavior weight roll |
| `:idle` | `:touching` | behavior weight roll |
| `:walking` | `:idle` | timeout or edge reached |
| `:walking` | `:running` | random burst chance |
| `:walking` | `:climbing` | reached climbable surface + roll (Phase 5+) |
| `:walking` | `:jumping` | random roll |
| `:sitting` | `:sleeping` | sit animation completes |
| `:sleeping` | `:idle` | timeout |
| `:climbing` | `:climbing-idle` | reached top or random pause |
| `:climbing-idle` | `:jumping` | timeout → jump off wall |
| `:jumping` | `:falling` | arc apex reached |
| `:falling` | `:landing` | hit a surface |
| `:landing` | `:idle` | stunned anim completes |
| any | `:being-hit` | click on cat / fast scroll (P0 interrupt) |
| `:being-hit` | `:stunned` | animation completes |
| `:stunned` | `:idle` | animation completes |

## Physics Model

Simple Euler integration, proven by DyberPet:

```
Constants (tunable):
  GRAVITY        = 0.5    px/tick²
  TERMINAL_VEL   = 12     px/tick
  JUMP_VX        = 3      px/tick (horizontal)
  JUMP_VY        = -8     px/tick (initial upward)
  CLIMB_SPEED    = 1.5    px/tick

Per tick (when airborne):
  vy = min(vy + GRAVITY, TERMINAL_VEL)
  x  = x + vx
  y  = y + vy

Landing impact:
  vy > 6   → :stunned (hard landing)
  vy > 3   → brief :landing
  else     → :idle (gentle touch)
```

Jump sprite frame mapping: `frame-index = floor(arc-progress × num-frames)`. Frames 0-1: launch, 2-3: rising, 4: apex, 5-6: falling, 7: landing prep.

## Behavior Catalog

### Priority System

| Priority | Category | Examples |
|----------|----------|---------|
| P0 (Immediate) | User reaction | Click startle, being-hit |
| P1 (Interrupt) | Environmental | Scroll tumble, element removed → fall |
| P2 (Planned) | Exploration | Climb, jump, approach element |
| P3 (Ambient) | Background | Idle, walk, sit, sleep |

Higher priority always interrupts lower. Same priority: current behavior completes first.

### Behaviors by Phase

**Phase 1 — Use Unused Sprites (no DOM queries)**

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `run-burst` | `:run` | Random chance to run instead of walk. Same movement, faster speed. |
| `jump-in-place` | `:jump` | Parabolic arc using gravity model. First use of y-coordinate. |
| `being-hit-reaction` | `:being-hit` → `:stunned` | Click on cat container triggers reaction. Requires `pointer-events: auto` on sprite. |

**Phase 2 — User Reactivity (event listeners, no DOM queries)**

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `cursor-tracking` | `:idle` (facing) | Throttled mousemove (200ms). Cat faces toward cursor. High personality-per-LOC. |
| `click-startle` | `:jump` / `:run` | Click within 200px → startle. Within 500px → face toward. 30% chance (not annoying). |
| `scroll-tumble` | `:being-hit` → `:stunned` | Fast scroll → being-hit. Slow scroll → ignore. |

**Phase 3 — DOM Awareness (element discovery)**

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `perch-on-element` | `:walk` → `:jump` → `:idle`/`:sit` | Walk to element, jump up, land on top. Surface = elements where `width > 100px, height > 30px`, visible in viewport. |
| `edge-walking` | `:walk` / `:run` | Walk along top edge of element, bounded by rect. Turn around or jump off at edges. |
| `curious-approach` | `:walk` → `:touch` | Walk toward interesting element (image, button), play touch animation facing it. |
| `gravity-fall` | `:jump` (falling frames) → `:stunned` | Element scrolls away or is removed → cat falls with gravity. Check `el.isConnected` per tick. |

**Phase 4 — Climbing (vertical movement)**

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `element-climbing` | `:climb` → `:climb-idle` | Climb tall elements (sidebar, tall images). `rect.height > 200px`. Constant vertical speed. |
| `platform-jumping` | `:jump` with arc | Jump between elements. Horizontal < 300px, feasibility check. Parabolic trajectory. |

**Phase 5 — Advanced (mood-gated, optional)**

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `sleep-in-shadows` | `:walk` → `:sit` → `:sleep` | Find dark elements via luminance (`0.299R + 0.587G + 0.114B < 80`). Walk there, sleep longer. |
| `mutation-investigator` | `:walk` → `:touch` | MutationObserver detects new content → cat investigates. Throttle to 1 per 10s. |

## Mood & Personality System

### Needs (Phase 3+)

Start with energy only (MVP). Add curiosity and comfort incrementally.

| Need | Range | Drains when | Fills when | Low → behavior |
|------|-------|------------|-----------|----------------|
| **Energy** | 0.0–1.0 | Active (walk, run, climb, jump) | Sleeping, sitting | More sitting/sleeping, shorter active bursts |
| **Curiosity** | 0.0–1.0 | Sitting, sleeping | Walking, exploring, mouse nearby | More walking, seeking unexplored areas |
| **Comfort** | 0.0–1.0 | Moving, being startled | Known spots, sleeping | Seeks favorite-spot, longer sleep |

### Dynamic Behavior Weights

Replace fixed `pick-next-behavior` with mood-derived weights (normalized to sum 1.0):

```
walking   = 0.3 × energy × curiosity
sitting   = 0.3 × (1 - energy)
sleeping  = 0.2 × (1 - energy) × (1 - curiosity)
meowing   = 0.15 × curiosity
touching  = 0.1 × comfort
exploring = 0.2 × curiosity × energy
```

### Physics → Mood Feedback

| Event | Mood Effect |
|-------|------------|
| Hard landing | energy -0.15, comfort -0.2 |
| Jump start | energy -0.05 |
| Climb reached top | curiosity -0.1, comfort +0.05 |
| Falling (per tick) | comfort -0.02 |

### Personality Traits (Phase 4+)

Three axes, each -1.0 to 1.0. Applied as multipliers on mood-derived weights.

| Trait | -1.0 | +1.0 |
|-------|------|------|
| **Adventurous** | Homebody | Explorer |
| **Playful** | Dignified | Playful |
| **Sociable** | Independent | Sociable |

Personality is config (constant). Mood is variable. Together: consistent yet dynamic.

### Preset Personalities

| Name | Adventurous | Playful | Sociable |
|------|------------|---------|----------|
| Lazy Tabby | -0.7 | -0.3 | 0.2 |
| Curious Kitten | 0.8 | 0.9 | 0.7 |
| Dignified Cat | 0.1 | -0.8 | -0.5 |
| Friendly Cat | 0.3 | 0.4 | 0.9 |

### Memory Model (Phase 4+)

Minimal spatial memory:

```
{:visited-spots []         ;; ring buffer, max 20, 50px buckets
 :favorite-spot nil         ;; most-visited bucket → drift toward when comfort is low
 :last-startle-x nil        ;; temporary avoidance zone, decays after ~30s
 :page-extent-seen [0 0]}   ;; [leftmost rightmost] → curiosity drives toward unexplored
```

## Emergent Micro-Stories

These arise from the rules without explicit scripting:

1. **Nap Spot** — Cat repeatedly sleeps near center. Over time, the `favorite-spot` bucket solidifies. User sees: "It has a favorite sleeping spot."

2. **Explorer** — High curiosity drives the cat toward unexplored page edges (`page-extent-seen` boundaries). User sees: "It's mapping out the page."

3. **Scared of Corner** — User clicks near cat at x=600. `last-startle-x` set. Next walk cycle, cat turns around before reaching 600. User sees: "It remembers being scared there." (Decays after ~30s.)

4. **Attention Seeker** — Sociable cat + mouse activity. Mouse proximity boosts curiosity. Cat follows mouse direction. Mouse absent → cat explores alone. User sees: "It wanted my attention and went off to sulk."

5. **Winding Down** — Energy drains over ~5 minutes. Walk durations shorten. Sitting increases. Eventually sleeps. Wakes refreshed, burst of activity. User sees: "It has a daily rhythm."

6. **Proud Climber** — High curiosity cat encounters tall element. Climbs, reaches top, climb-idle, sits, meows. User sees: "It climbed that image and is proud."

Note: Stories 1 and 3 require explicit "seek-then-act" behavior wiring (walk toward favorite-spot/avoidance zone), not just weight changes. Story 6 requires DOM awareness from Phase 3+.

## Implementation Phases

### Phase 0: Timer Unification (prerequisite)
- Replace dual `setInterval` with single `requestAnimationFrame` loop
- Delta-time accumulation for animation frames and state ticks
- Verify existing walk/idle behavior is unchanged
- **Refactor only, no new features**

### Phase 1: Coordinate System + First Sprites
- Add `y` coordinate to state
- Switch container from `left`/`bottom` to `transform: translate3d(x, y, 0)` + `will-change: transform`
- Add `run-burst`: random chance to switch walk → run, `:run` sprite
- Add `jump-in-place`: parabolic arc with gravity, `:jump` sprite, landing → idle
- Add `being-hit-reaction`: click on cat → `:being-hit` → `:stunned` → `:idle`
- **First use of all 6 unused sprites except climb pair**

### Phase 2: User Reactivity
- Add event listener tracking infrastructure (register in state, teardown in `stop!`)
- `cursor-tracking`: mousemove (throttled 200ms) → update facing direction
- `click-startle`: click distance check → being-hit/run away
- `scroll-tumble`: fast scroll → being-hit
- Mouse state in separate atom, enriched into `uf-data`
- **Highest personality-per-LOC ratio. Cat feels alive.**

### Phase 3: Energy + DOM Awareness
- Add energy need (single mood dimension)
- Energy drains while active, fills while sleeping/sitting
- `weighted-behavior-selection` replaces `pick-next-behavior`
- DOM scan effect: `querySelectorAll` → `getBoundingClientRect` → filter viable surfaces
- Cache surfaces, re-query on scroll/resize (debounced) or every ~10s
- `perch-on-element`: walk to element → jump up → land on top → idle/sit/sleep
- `edge-walking`: walk along element top edge
- `gravity-fall`: element removed/scrolled away → cat falls (check `el.isConnected`)
- Graceful degradation: no interesting elements found → cat stays on bottom bar
- **Cat gains spatial awareness and behavioral arcs**

### Phase 4: Climbing + Personality
- `element-climbing`: `:climb` sprite, constant vertical speed on tall elements
- `climb-idle` at top → jump off or perch
- `platform-jumping`: parabolic arcs between elements (feasibility check)
- Add curiosity + comfort needs
- Add personality traits as config multipliers
- Add memory model (visited-spots, favorite-spot, last-startle-x)
- Preset personalities selectable
- **Cat becomes a character**

### Phase 5: Polish + Advanced
- `sleep-in-shadows`: luminance detection
- `curious-approach`: walk to images/buttons, touch animation
- Physics → mood feedback (landing impacts, climb satisfaction)
- Scene vectors for complex behavior sequences (optional)
- Tune mood rates for 5-minute behavioral arcs
- Cross-site testing and graceful degradation hardening

## Technical Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| SCI performance with rAF | Medium | Profile early. If slow, reduce physics to 15fps with visual interpolation. |
| Layout thrashing from getBoundingClientRect | High | Batch reads, cache aggressively, event-driven invalidation. Never interleave reads and writes. |
| Event listener leaks | High | Track every listener in state. Remove all in `stop!`. Add infra in Phase 2 before any listeners. |
| Cross-site CSS conflicts | Medium | Max z-index already used. Consider Shadow DOM container if clipping observed. |
| Element removed mid-climb | Medium | Check `el.isConnected` per physics tick. If false → immediate `:falling`. |
| Mood parameter tuning | Medium | Start with energy-only. Add needs one at a time. Add noise to rates for surprise. |
| SPA page navigation | Known | Destroys Scittle runtime. Wrap navigation in `js/setTimeout`. No new mitigation needed. |
| Pages without interesting elements | Low | Graceful degradation: bottom-bar-only mode when no surfaces found. |
| MutationObserver noise on React pages | Medium | Defer to Phase 5. Filter by `addedNodes` size, debounce by 2s. |

## Performance Budget

| Operation | Cost per tick | Phase |
|-----------|--------------|-------|
| Physics (Euler step) | ~0.01ms | 1+ |
| Mood update | ~0.01ms | 3+ |
| Sprite frame advance | ~0.01ms | 0+ |
| `translate3d` write | ~0.01ms | 1+ |
| `dispatch!` overhead | ~0.02ms | 0+ |
| `getBoundingClientRect` × N surfaces | ~0.1-0.5ms | 3+ (every ~5-10s) |
| Mouse event (stored, not per-tick) | 0ms | 2+ |

Total per-frame: ~0.1ms (Phases 0-2), ~0.2-0.6ms (Phase 3+ during surface scans). Well within 16ms frame budget.

**Rule**: The cat is decorative. It must never cause visible jank on the host page.

## Cross-Cutting Concerns

### Cross-Site Robustness

- Element discovery uses tag names (`nav`, `header`, `aside`, `img`) + role attributes + class patterns
- Many sites use custom components, shadow DOM, or non-semantic markup
- **Graceful degradation is mandatory**: if no interesting elements → bottom-bar-only mode
- Shadow DOM contents are unreachable — accept this, stick to light DOM
- `overflow: hidden` ancestors could clip the cat — check before perching inside elements

### Position Mode Transitions

When the cat moves between bottom bar and element surfaces:
- Bottom bar: `position: fixed`, coordinates are viewport-relative
- On elements: still `position: fixed` but tracking element's viewport rect
- Must re-query `getBoundingClientRect` while on/near elements (coarsely, not every frame)
- Element scrolling out of view → cat falls (gravity)

### Scroll Handling

`position: fixed` means the cat stays in viewport naturally. Surfaces are calculated viewport-relative via `getBoundingClientRect` (also viewport-relative). Scroll events trigger:
- Surface position recalculation (debounced)
- Possible `scroll-tumble` reaction at P1 priority
- If on-element and element scrolls out → gravity-fall

---

*Plan produced from 3 parallel brainstorm agents (DOM-aware behaviors, movement systems, personality/mood) and 3 parallel review agents (A+B, A+C, B+C review). Reviews validated emergent stories, identified integration gaps, refined phasing, and surfaced risks.*
