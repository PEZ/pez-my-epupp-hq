# Page Buddy — Behavior Roadmap

Evolving the page buddy cat from a bottom-bar walker into a DOM-aware, personality-driven companion that creates emergent micro-stories on any web page. Inspired by Shimeji.

## Current State

Phases 0–2 are complete. The cat walks, runs, jumps, sits, sleeps, meows, reacts to clicks, faces the mouse cursor, and tumbles on fast scrolls. All movement uses Euler gravity. It walks on the bottom bar only — no DOM climbing yet.

| Animation | Frames | Status |
|-----------|--------|--------|
| walk | 3 | In use — `:walking` |
| idle | 8 | In use — `:idle` |
| run | 5 | In use — `:running` |
| sit | 10 | In use — `:sitting` |
| sleep | 12 | In use — `:sleeping` |
| jump | 8 | In use — `:jumping`/`:falling` |
| meow | 3 | In use — `:meowing` |
| climb | 7 | **Unused** (Phase 4) |
| climb-idle | 4 | **Unused** (Phase 4) |
| touch | 3 | In use — `:touching` |
| being-hit | 3 | In use — `:being-hit` |
| stunned | 6 | In use — `:stunned`/`:landing` |

Architecture: Uniflow adapted for Epupp/SCI without Replicant. Three atoms (`!state`, `!env`, `!timing`), single `requestAnimationFrame` loop with delta-time accumulation, `dispatch!` as single state access point. See [SKILL.md](../.github/skills/page-buddy/SKILL.md) for architecture invariants, FSM, physics model, and naming conventions.

## Prior Art

| Project | Key Pattern | Takeaway |
|---------|------------|----------|
| **Shimeji** (Java) | Window edges as physical platforms, BorderType system (floor/wall/ceiling), behavior-tree XML, **drag-and-throw**, breed/multiply | Treat DOM elements as surfaces. Gravity when surface disappears. Dragging is the iconic interaction. |
| **eSheep** (Windows) | Walk on title bars, fall between windows, parachute | Gravity simulation is essential for believability |
| **Neko** (X11) | Chase mouse cursor, sleep when idle | Cursor reactivity = maximum personality for minimum code |
| **DyberPet** (Python) | Euler gravity (`vy += gravity`), drag-release velocity | Simple physics model that works. Drag velocity → throw arc. |
| **Tamagotchi** | Hidden needs meters drive behavior | Users infer personality from simple meter interactions |
| **The Sims** | Personality modulates *strategy*, not *goals* | Traits as multipliers on behavior weights |
| **Dwarf Fortress** | `personality × memory × needs → behavior_weights` | Simple rules → emergent stories |
| **Bonzi Buddy** | Intrusive, interruptive, adware | **Never** block page interaction. Ambient > interruptive. |

**Core lesson**: Reactivity to user input + behavioral rhythm + consistency = perceived personality. Users anthropomorphize the rest.

## Architecture

Architecture invariants, the three-atom model, uf-data enrichment, behavior FSM, physics constants, and action/effect naming conventions are documented in [SKILL.md](../.github/skills/page-buddy/SKILL.md). This section covers only forward-looking architectural decisions for future phases.

### DOM Queries as Effects (Phase 3+)

DOM reads (`getBoundingClientRect`, `getComputedStyle`) are impure. Route them through the effect layer:

```
action decides to explore
  → effect queries DOM
  → dispatch result as new action
  → pure handler processes data
```

Cache surface data, re-query only every ~5-10s or on scroll/resize events.

### Surface Data Architecture (Phase 3+)

| Data | Atom | Rationale |
|------|------|-----------|
| **Available surfaces** (DOM scan results) | `!env` | Environment sensor data — like mouse position. Acquired impurely, changes with scroll/resize, consumed read-only by actions via `uf-data`. |
| **Current surface** (which element the cat stands on) | `!state` | Application decision — the cat *chose* to land here. Physics and edge-walking logic depend on it. |
| **Surface validity** (`el.isConnected`) | Effect | Impure DOM read. Effect checks validity and dispatches `:buddy/ax.surface-lost` if element is gone. |

Flow: `:dom/fx.scan-surfaces` effect → writes `{:surfaces [...]}` to `!env` → `dispatch!` snapshots into `uf-data` as `:surfaces/visible` → actions use it for pathfinding/perching decisions. Store element refs in `!state`, but re-query rects via `uf-data` — never cache rects as stable.

### Layered Decision Architecture (Phase 3+)

Separate behavior decisions into layers:

1. **Physics layer**: Determines which transitions are physically possible (can't walk if airborne, can't climb without adjacent surface). Output: set of allowed transitions.
2. **Behavior layer**: From the allowed set, selects which transition to take using mood weights + personality. Surface-aware behavior pools: different behavior weights when on floor vs on element vs climbing.
3. **Animation layer**: Maps state → sprite. Always a pure lookup.

### Drag Architecture (Phase 3+)

Drag state lives in `!env` (same pattern as mouse tracking):

```clojure
;; !env additions
{:drag {:active? false :x nil :y nil :vx 0 :vy 0}}
```

- `:dom/fx.add-drag-handler` — `mousedown` on cat → sets `:drag :active? true`, tracks `mousemove` coords + velocity, `mouseup` → dispatches `[:buddy/ax.drag-release]`
- While dragged: tick action positions cat at `(:drag/x uf-data)` / `(:drag/y uf-data)`
- On release: transition to `:falling` with velocity from drag state, existing gravity handles the arc

## Behavior Catalog

### Priority System

| Priority | Category | Examples |
|----------|----------|---------|
| P0 (Immediate) | User reaction | Drag grab, click startle, being-hit |
| P1 (Interrupt) | Environmental | Scroll tumble, surface removed → fall |
| P2 (Planned) | Exploration | Climb, jump, approach element, perch |
| P3 (Ambient) | Background | Idle, walk, sit, sleep, self-groom |

Higher priority always interrupts lower. Same priority: current behavior completes first.

### Implemented Behaviors (Phases 1–2) ✅

| Behavior | Description |
|----------|-------------|
| `run-burst` | Random chance to run instead of walk. `:run` sprite, faster speed. |
| `jump-in-place` | Parabolic arc with Euler gravity. `:jump` sprite. Landing impact → stunned or idle. |
| `being-hit-reaction` | Click on cat → `:being-hit` → `:stunned` → `:idle`. |
| `cursor-tracking` | Throttled mousemove (200ms). Cat faces toward cursor. |
| `click-startle` | Click within 200px → being-hit/run away. Within 500px → face toward. 30% chance. |
| `scroll-tumble` | Fast scroll (dy > 500) → `:being-hit`. |

### Phase 3 — DOM Awareness + Dragging

#### Phase 3α: Surface Discovery + Perching

The minimum viable "shimeji moment" — cat jumps onto page elements.

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `perch-on-element` | `:walk` → `:jump` → `:idle`/`:sit` | Walk to element, jump up, land on top. Surface = elements where `width > 100px, height > 30px`, visible in viewport. |
| `gravity-fall` | `:jump` (falling frames) → `:stunned` | Element scrolls away or is removed → cat falls with gravity. Check `el.isConnected` per tick. |

Infrastructure:
- New effect: `:dom/fx.scan-surfaces` — `querySelectorAll` → `getBoundingClientRect` → filter viable surfaces → write to `!env`
- Add `:current-surface` to `!state` (nil = floor)
- Generalize ground plane: `current-ground-y` reads from `:current-surface` rect or falls back to `floor-y`
- Modify `tick-jumping` to check against surface rects during arc (not just `floor-y`)
- Debounced re-scan on scroll/resize (new listeners in `!env`)
- Graceful degradation: no interesting elements → cat stays on floor

#### Phase 3β: Edge Walking + Gravity Fall

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `edge-walking` | `:walk` / `:run` | Walk along top edge of element, bounded by rect. Turn around or jump off at edges. |
| `reluctant-edge-departure` | `:idle` (pause) → turn or jump | Cat reaches element edge, pauses facing the drop for ~1s, then turns around or jumps. Makes edges feel like real decisions. |

#### Phase 3γ: Mouse Dragging (Shimeji-inspired)

Shimeji's most iconic interaction. High personality-per-LOC with existing physics.

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `drag-and-throw` | `:being-hit` (while held) → `:jump` (thrown) | `mousedown` on cat → follow cursor. `mouseup` → launch with mouse velocity. Existing gravity handles the arc. |
| `resist-drag` | `:being-hit` (squirm) → `:jump` (break free) | After being held ~2s, cat squirms and breaks free. Probability roll per tick. |

#### Phase 3δ: Energy System

Independent of DOM awareness — purely changes behavioral variety.

- Add `energy` to `!state` (0.0–1.0, starts at 0.8)
- Energy drains while walking/running/jumping; fills while sitting/sleeping
- `weighted-behavior-selection` replaces `pick-next-behavior`
- Low energy → more sitting/sleeping, shorter walks
- High energy after sleeping → active burst (the "winding down" story)

#### Phase 3ε: Active Cursor Chase (Shimeji-inspired)

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `cursor-chase` | `:walk` / `:run` | Cat walks/runs toward stationary cursor position. Triggered when mouse is near cat and stationary for >2s. Low frequency to avoid annoyance. |
| `cursor-orbiting` | `:walk` | When sociable trait is high, cat slowly approaches stationary cursor, stopping just outside click-startle range. "Curious but cautious." |

### Phase 4 — Climbing + Personality

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `element-climbing` | `:climb` → `:climb-idle` | Climb tall elements (sidebar, tall images). `rect.height > 200px`. Constant vertical speed. |
| `climb-idle` | `:climb-idle` | Pause at top. Jump off or perch. |
| `platform-jumping` | `:jump` with arc | Jump between elements. Horizontal < 300px, feasibility check. Parabolic trajectory. |
| `edge-peeking` | `:climb` (hanging) | At element boundary, cat hangs half-body over the edge, peeks down, pulls back. Uses `climb` frames with negative offset. |
| `hotspot-regions` | varies | Different click reactions based on where on the sprite you click. Upper click → meow, lower → being-hit. |
| `self-grooming` | `:touch` | Play `touch` animation without direction change. Triggered after landing, waking from sleep, or idle timeout. |
| `startle-chain` | `:being-hit` → `:run` | When energy is high: being-hit → run away (not just stunned). "Panicked dash." |

### Phase 5 — Advanced (mood-gated, optional)

| Behavior | Animations | Description |
|----------|-----------|-------------|
| `sleep-in-shadows` | `:walk` → `:sit` → `:sleep` | Find dark elements via luminance (`0.299R + 0.587G + 0.114B < 80`). Walk there, sleep longer. |
| `curious-approach` | `:walk` → `:touch` | Walk toward interesting element (image, button), play touch animation facing it. |
| `mutation-investigator` | `:walk` → `:touch` | MutationObserver detects new content → cat investigates. Throttle to 1 per 10s. |
| `ceiling-walking` | `:walk` (flipped) | Walk upside-down along bottom edge of elements. Sprite flipped via CSS `scaleY(-1)`. |
| `breed-multiply` | all | Spawn a second cat on page. Share single rAF loop. Independent state machines. |
| `element-nudging` | `:touch` | Cat walks against element edge, plays touch, applies tiny CSS transform (2-3px) to the element. Reversible. |

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
 :favorite-perch nil        ;; element where cat has slept → preferentially returns
 :last-startle-x nil        ;; temporary avoidance zone, decays after ~30s
 :last-throw-dir nil        ;; direction of last drag-throw → avoidance, decays after ~20s
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

7. **Throw Memory** — User drags and throws cat to the right. Cat avoids walking right for ~20s (`last-throw-dir`). User sees: "It remembers being thrown that way."

8. **Favorite Perch** — Cat sleeps on a nav bar. Next time energy is low, it walks toward that element and jumps up again. User sees: "It has a favorite spot on the nav bar."

9. **Curious but Cautious** — Sociable cat slowly orbits stationary cursor, stopping just outside click range. User moves mouse → cat retreats. User sees: "It wants my attention but is shy."

10. **Panicked Dash** — Fast scroll → being-hit → if energy is high, cat runs away from center instead of going stunned. User sees: "The scroll scared it into running."

Note: Stories 1, 3, 7, and 8 require explicit "seek-then-act" behavior wiring, not just weight changes. Story 6 requires DOM awareness from Phase 3+. Story 9 requires Phase 3ε cursor chase.

## Implementation Phases

### Phase 0: Timer Unification ✅
Single `requestAnimationFrame` loop with delta-time accumulation. Replaced dual `setInterval`.

### Phase 1: Coordinate System + First Sprites ✅
`{x, y}` coordinates, `translate3d` rendering, run-burst, jump with gravity, being-hit reaction. All sprites except climb pair now in use.

### Phase 2: User Reactivity ✅
Event listener lifecycle (`!env` + cleanup in `ax.stop`), cursor tracking, click startle, scroll tumble. Mouse/scroll state in `!env`, enriched into `uf-data`.

### Phase 3: DOM Awareness + Dragging

Decomposed into independently shippable sub-phases:

**Phase 3α: Surface Discovery + Perching** — The "shimeji moment"
- New effect: `:dom/fx.scan-surfaces` → `querySelectorAll` → `getBoundingClientRect` → filter → write to `!env`
- Add `:current-surface` to `!state` (nil = floor)
- Generalize ground plane: check surface rects during jump arcs, not just `floor-y`
- `perch-on-element`: walk to element → jump → land on top → idle/sit
- `gravity-fall`: surface removed/scrolled away → fall with existing gravity
- Debounced re-scan on scroll/resize
- Graceful degradation: no surfaces → cat stays on floor
- **Visible result**: Cat jumps onto a nav bar and sits there.

**Phase 3β: Edge Walking**
- Walk along element top edge, bounded by element's left/right edges
- Turn around at boundary or jump off
- `reluctant-edge-departure`: pause at edge, decide whether to turn or jump
- **Visible result**: Cat walks along a header, contemplates the drop.

**Phase 3γ: Mouse Dragging** (Shimeji's iconic interaction)
- `mousedown` on cat → drag state in `!env` → follow cursor
- `mouseup` → `:falling` with velocity from mouse movement
- Resist-drag: after ~2s held, probability of breaking free
- Existing gravity handles the throw arc. Existing surface detection handles where it lands.
- **Visible result**: Pick up cat, toss it onto an element. Endlessly satisfying.

**Phase 3δ: Energy System**
- Add `energy` to `!state` (0.0–1.0)
- Drain while active, fill while resting
- `weighted-behavior-selection` replaces `pick-next-behavior`
- Independent of DOM awareness — can ship in any order
- **Visible result**: Cat has rest/activity rhythms. "Winding down" story emerges.

**Phase 3ε: Active Cursor Chase**
- Cat walks/runs toward stationary cursor when nearby for >2s
- Low frequency to avoid annoyance
- Sociable personality → cursor orbiting behavior
- **Visible result**: "It's following my mouse!"

### Phase 4: Climbing + Personality
- `element-climbing`: `:climb` sprite, constant vertical speed on tall elements
- `climb-idle` at top → jump off or perch
- `platform-jumping`: parabolic arcs between elements (feasibility check)
- `edge-peeking`: hang over element edge, peek down
- `self-grooming`, `startle-chain`, `hotspot-regions` (see Behavior Catalog)
- Add curiosity + comfort needs
- Add personality traits as config multipliers
- Add memory model (visited-spots, favorite-spot, favorite-perch, last-startle-x)
- Preset personalities selectable
- **Cat becomes a character**

### Phase 5: Polish + Advanced
- `sleep-in-shadows`: luminance detection
- `curious-approach`: walk to images/buttons, touch animation
- `ceiling-walking`: upside-down walking on element undersides
- `breed-multiply`: spawn second cat, shared rAF loop
- `element-nudging`: tiny reversible CSS transform on touched elements
- Physics → mood feedback (landing impacts, climb satisfaction)
- Tune mood rates for 5-minute behavioral arcs
- Cross-site testing and graceful degradation hardening

## Technical Risks

| Risk | Severity | Status |
|------|----------|--------|
| SCI performance with rAF | Medium | **Resolved** — rAF works fine, no fallback needed. |
| Event listener leaks | High | **Mitigated** — infrastructure in place, tested through Phase 2. |
| Layout thrashing from getBoundingClientRect | High | Batch reads, cache aggressively, event-driven invalidation. Never interleave reads and writes. |
| Cross-site CSS conflicts | Medium | Max z-index already used. Consider Shadow DOM container if clipping observed. |
| Element removed mid-climb | Medium | Check `el.isConnected` per physics tick. If false → immediate `:falling`. |
| Drag interaction on touch devices | Medium | Touch events differ from mouse. Start with mouse-only, add touch in Phase 5. |
| Mood parameter tuning | Medium | Start with energy-only. Add needs one at a time. Add noise to rates for surprise. |
| SPA page navigation | Known | Destroys Scittle runtime. Wrap navigation in `js/setTimeout`. No new mitigation needed. |
| Pages without interesting elements | Low | Graceful degradation: bottom-bar-only mode when no surfaces found. |
| MutationObserver noise on React pages | Medium | Defer to Phase 5. Filter by `addedNodes` size, debounce by 2s. |
| Multiple cat instances (breed) | Medium | Share single rAF loop. Independent state atoms. Coordinate via shared `!env`. |

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

*Original plan produced from 3 parallel brainstorm agents + 3 cross-review agents. Updated after Phases 0–2 completion with Shimeji behavior gap analysis from 3 parallel research agents (status/architecture alignment, Shimeji behavior catalog, Phase 3 readiness/decomposition).*
