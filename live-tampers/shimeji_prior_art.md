# Shimeji Prior Art Reference

Analysis of the Shimeji desktop mascot system (Shimeji-ee / Shimeji-Desktop) as prior art for the page buddy. Based on the open source Java implementation's behavior/action XML configs and source code.

**Sources**: [DalekCraft2/Shimeji-Desktop](https://github.com/DalekCraft2/Shimeji-Desktop), [gil/shimeji-ee](https://github.com/gil/shimeji-ee)

---

## Architecture Overview

Shimeji uses a **behavior → action** two-layer system:

- **Behaviors**: High-level states (Idle, Walk, Climb, Fall, etc.) with weighted random transitions
- **Actions**: Low-level movement primitives (Move, Stay, Fall, Jump, Animate, Sequence, Select)

Transitions between behaviors are XML-configured with conditions (surface contact, position, population count) and weighted random selection (Frequency attribute). Each behavior chains to a `NextBehaviorList` with `Add` flag controlling whether candidates replace or augment the global pool.

## Behavior Catalog

### System Behaviors (always required)
| Behavior | Purpose |
|----------|---------|
| Fall | Generic falling — gravity + air resistance |
| Dragged | While user holds the mascot |
| Thrown | Post-release physics (inherits drag velocity) |
| ChaseMouse | Active cursor chase routine |

### Floor Behaviors
| Behavior | Purpose |
|----------|---------|
| StandUp | Standing idle/reset |
| SitDown | Sitting idle |
| SitWhileDanglingLegs | Seated idle variation (on edge) |
| LieDown | Prone idle/rest |
| WalkAlongWorkAreaFloor | Walk on screen bottom |
| RunAlongWorkAreaFloor | Run on screen bottom |
| CrawlAlongWorkAreaFloor | Slow crawl |
| WalkLeft/RightAlongFloorAndSit | Walk then sit |
| GrabWorkAreaBottomLeft/RightWall | Reach wall from floor |
| RunAndGrabBottomLeft/RightWall | Run to wall |
| JumpFromBottomOfIE | Jump off window bottom |
| SplitIntoTwo | Breed/spawn another mascot |
| PullUpShimeji | Summon another mascot |

### Wall Behaviors
| Behavior | Purpose |
|----------|---------|
| HoldOntoWall | Cling to wall stationary |
| FallFromWall | Detach and drop |
| ClimbHalfwayAlongWall | Climb partway up |
| ClimbAlongWall | Full wall climb |
| JumpFromLeft/RightWall | Jump off wall |

### Ceiling Behaviors
| Behavior | Purpose |
|----------|---------|
| HoldOntoCeiling | Hang upside-down stationary |
| FallFromCeiling | Detach and drop |
| ClimbAlongCeiling | Traverse ceiling horizontally |

### Window (IE) Behaviors
| Behavior | Purpose |
|----------|---------|
| WalkAlongIECeiling | Walk on window top edge |
| RunAlongIECeiling | Run on window top |
| CrawlAlongIECeiling | Slow crawl on window top |
| SitOnTheLeft/RightEdgeOfIE | Sit at window edge |
| JumpFromLeft/RightEdgeOfIE | Jump off window edge |
| WalkLeft/RightAlongIEAndSit | Walk then sit on window |
| WalkLeft/RightAlongIEAndJump | Walk then jump off window |
| HoldOntoIEWall | Cling to window side |
| ClimbIEWall | Climb window side |
| ClimbIEBottom | Climb along window bottom |
| GrabIEBottomLeft/RightWall | Reach window wall from bottom |
| JumpOnIELeft/RightWall | Jump onto window side |
| ThrowIEFromLeft/Right | Pick up window and throw it |
| RunAndThrowIEFromLeft/Right | Run to window edge, throw it |

## Action System

### Action Types
| Type | Purpose |
|------|---------|
| Move | Target-driven movement with animated poses |
| Stay | Stationary over time (idle, cling) |
| Fall | Gravity-driven descent with air resistance |
| Jump | Velocity-parameterized arc |
| Animate | Pure animation sequence |
| Sequence | Ordered action chain (with optional Loop) |
| Select | Branch/choice among child actions |
| Embedded | Java class-backed action |

### Physics Parameters
| Parameter | Meaning | Example Values |
|-----------|---------|---------------|
| Gravity | Downward acceleration per tick | 2 (fall), 0.5 (thrown IE) |
| RegistanceX | Horizontal air drag (0-1) | 0.05 |
| RegistanceY | Vertical air drag (0-1) | 0.1 |
| InitialVX | Launch horizontal velocity | varies |
| InitialVY | Launch vertical velocity | varies |
| VelocityParam | Target-relative velocity scaling | used in Jump |
| TargetX, TargetY | Movement target coordinates | screen edge, cursor, etc. |

### Border Types
Actions declare which surface they operate on:
- **Floor** — walking, standing, sitting, landing
- **Wall** — climbing, grabbing, clinging
- **Ceiling** — crawling, hanging

### Condition System
Conditions use context variables:
- `mascot.environment.floor.isOn(anchor)` — on floor?
- `mascot.environment.wall.isOn(anchor)` — on wall?
- `mascot.environment.ceiling.isOn(anchor)` — on ceiling?
- `mascot.environment.workArea` borders (left, right, top, bottom)
- `mascot.environment.activeIE` borders (topBorder, leftBorder, rightBorder, bottomBorder)
- `mascot.environment.cursor` position
- `mascot.totalCount` — population cap
- `Math.random()` — probabilistic branching

## Transition System

### Weighted Random Selection
Each behavior has a `Frequency` attribute. When selecting the next behavior, the engine:
1. Filters candidates by their conditions (surface contact, position, etc.)
2. Selects randomly weighted by Frequency among valid candidates
3. Falls back to `Fall` behavior if no candidates are valid

### NextBehaviorList
A behavior can constrain what follows:
- `Add=false`: only listed candidates are eligible next
- `Add=true`: listed candidates are added to the global pool
- Each entry has its own Frequency weight

### Chaining
Behaviors like "WalkLeftAlongFloorAndSit" are `Sequence` actions: Walk → Sit. The sequence completes, then the behavior transition system picks the next behavior.

## Drag / Throw System

### Dragging
- Dragged behavior = looping Sequence: Pinched + Resisting
- Pinched pose selection uses `FootX` relative to `cursor.x` with multiple threshold bands (different poses based on how the mascot hangs from the grab point)
- The mascot follows the cursor directly

### Throwing
- On release, the Thrown behavior fires
- Launch velocity inherits from cursor drag delta (dx, dy between frames)
- Falls into normal Fall behavior with inherited velocity
- Air resistance (RegistanceX=0.05, RegistanceY=0.1) decelerates the throw

### Throw-IE (Window Throwing)
Unique Shimeji feature: the mascot can pick up and throw browser windows:
1. Walk/run to window edge
2. Fall carrying the window (FallWithIe)
3. Walk/run carrying window (WalkWithIe, RunWithIe)
4. Release: ThrowIe with Gravity=0.5 and launch velocity

## Wall / Ceiling System

### Wall Climbing
- ClimbWall is a Move action with `BorderType=Wall`
- Movement direction (up/down) determined by TargetY conditions
- Animation switches based on climb direction
- Speed: varies by behavior (Climb vs GrabWall transition)
- HoldOntoWall: stationary cling (Stay action)
- FallFromWall: detach → Fall behavior

### Ceiling
- ClimbAlongCeiling: Move action with `BorderType=Ceiling`
- Horizontal traversal while upside-down
- HoldOntoCeiling: stationary hang
- FallFromCeiling: detach → Fall behavior
- Separate transition from wall-top: wall climb → ceiling grab is a behavior transition

### Surface Transitions
Wall → Ceiling: Implicit through behavior conditions. When climbing reaches the top, wall condition becomes false + ceiling condition becomes true → behavior pool switches.

Floor → Wall: Explicit behaviors (GrabWorkAreaBottomLeftWall, etc.) that transition from floor walking to wall climbing.

## Position Model

- **Absolute screen coordinates** — mascot position is in screen space
- **Anchor point** — each pose has an ImageAnchor (e.g., 64,128) used for collision/surface checks
- **Window tracking**: IE/window-related behaviors track the "active IE" — when a window moves, the mascot checks if its anchor is still on the window border
- **No relative positioning** — Shimeji does NOT store position relative to windows. Position is always absolute screen coords. When a window moves, the mascot may detach and fall.

---

## Relevance to Page Buddy

### What Shimeji Does That Page Buddy Plans

| Feature | Shimeji | Page Buddy Plan |
|---------|---------|----------------|
| Floor walking | ✅ Walk, Run, Crawl speeds | ✅ Walk, Run |
| Wall climbing | ✅ Up/down, with cling idle | ✅ Planned Phase 5a |
| Ceiling traversal | ✅ Full ceiling walking | ✅ Planned Phase 5c |
| Drag + throw | ✅ Cursor velocity inheritance | ✅ Implemented |
| Gravity + air resistance | ✅ Gravity=2, Resistance X/Y | ✅ Implemented |
| Surface transitions | ✅ Floor→Wall, Wall→Ceiling via behavior conditions | ✅ Planned corner transitions |
| Behavior weights | ✅ Frequency-based random selection | ✅ Energy-weighted selection |
| Edge sitting | ✅ SitOnEdge, DangleLegs | ✅ Edge-contemplating |
| Cursor chasing | ✅ ChaseMouse behavior | ✅ Implemented |
| Sprite direction | ✅ LookRight + mirrored poses | ✅ Facing system |

### What Shimeji Does That Page Buddy Doesn't (Yet)

1. **Crawl speed variant**: Shimeji has Walk/Run/Crawl — three speed tiers. Page buddy has Walk/Run but no Crawl. A cautious/stalking mode at 0.5× speed would add variety.

2. **Dangling legs on edges**: SitWhileDanglingLegs — mascot sits on an edge with legs hanging over. Page buddy has edge-contemplating but no distinct "sitting on edge" animation.

3. **Multiple cling poses based on drag angle**: Shimeji's Dragged behavior selects from multiple poses based on where the grab point is relative to the cursor (threshold bands on FootX vs cursor.x). Page buddy uses a single being-hit animation during drag.

4. **Breeding/spawning**: SplitIntoTwo, PullUpShimeji — population mechanics. Not relevant for single-buddy, but multi-buddy could be interesting future work.

5. **Window manipulation**: ThrowIE — mascot picks up and throws browser windows. This is the signature Shimeji move. Page buddy could theoretically move DOM elements, but this is high-risk for page usability.

6. **Climb-halfway behavior**: ClimbHalfwayAlongWall — the mascot climbs partway up then comes back down. A natural-looking "exploration" behavior that page buddy's plan doesn't explicitly include.

7. **Bottom-of-window traversal**: ClimbIEBottom — mascot traverses the bottom edge of a window. Page buddy's Phase 5c covers ceiling (element bottom edges), which is analogous.

### What Page Buddy Does Better Than Shimeji

1. **Relative surface positioning**: Phase 4 introduces scroll-aware attachment — the cat moves with its element. Shimeji uses absolute screen coords and detaches when windows move. This is a significant improvement for web context.

2. **Energy system**: Page buddy's energy-driven behavior weights create organic behavior transitions (tired cat → sit → sleep). Shimeji uses static Frequency weights — no fatigue model.

3. **Mood/personality**: Page buddy plans personality traits (adventurous, playful). Shimeji has no personality model.

4. **Multi-step scenes**: Page buddy's goal + sub-state pattern (Box Explorer, Full Perimeter Walk) creates narrative arcs. Shimeji's Sequence actions are mechanical chains without thematic coherence.

5. **Surface-aware physics**: Page buddy's "no gravity while attached" model is cleaner than Shimeji's wall-climbing physics which fights gravity direction.

### Key Shimeji Insights for Page Buddy

1. **Gravity=2 is a tuned value**: Shimeji's default gravity of 2 px/tick² with air resistance (0.05 horizontal, 0.1 vertical) produces natural-looking falls. Page buddy could compare its gravity constant against these proven values.

2. **Border conditions are explicit, not derived**: Shimeji checks `floor.isOn(anchor)`, `wall.isOn(anchor)`, `ceiling.isOn(anchor)` as explicit boolean conditions. Page buddy's plan to derive surface type from geometry is more flexible but should ensure the derived value is available cheaply for condition checks.

3. **Throw velocity = cursor delta, not computed arc**: Shimeji's throw simply inherits cursor movement velocity (dx, dy). Page buddy already does this — validation that this is the right approach.

4. **Wall→Ceiling is a behavior transition, not a smooth animation**: Shimeji doesn't smoothly rotate around corners. It transitions between behaviors with condition checks (wall.isOn → ceiling.isOn). Page buddy's planned corner transitions with pause + rotation are more polished.

5. **Fall is the universal recovery state**: When no valid behavior is found, Shimeji falls back to Fall. This is a robust pattern — page buddy should ensure `:bs/falling` is the catch-all recovery.

6. **Pose-per-frame animation**: Shimeji defines velocity and duration per animation frame, not per state. This allows variable-speed animations within a single action. Page buddy uses constant frame rates per animation — could add per-frame timing for more expressive movement.

7. **No "on floor" vs "on element" distinction**: Shimeji treats all surfaces uniformly — screen edge is a border, window edge is a border, same system. Page buddy distinguishes floor (bottom bar, no element) from surfaces (elements). This is correct for web context but adds complexity.
