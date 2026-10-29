---
name: cellsify
description: "Build, drive, and verify Cellsify projects from the editor with the pez.cellsify userscript library: projects as data, push and pull, evaluate through REPL cells, look at the user content frame. Use when: working with Cellsify, cellsify.com, pez.cellsify or userscripts/pez/cellsify.cljs, building or changing a Cellsify project, sheet, cell, formula, named ref, control (grid, button, timer, canvas) or action, evaluating forms inside Cellsify, cellsify_life.cljs, reverse engineering Cellsify messages or export text, or updating the library in Epupp."
---

# Cellsify

Cellsify is a Scittle spreadsheet at cellsify.com. `pez.cellsify` is an Epupp userscript that auto-runs on `https://cellsify.com/app/*` and lets you build and drive Cellsify projects from the editor, as data.

## The system

Two frames, one project.

- **The app** is the top frame at `cellsify.com/app`. Epupp, the REPL, and `pez.cellsify` live here.
- **The user content frame** is a cross-origin frame on `cellsify-usercontent.com`. The grid lives there and formulas, timers, and actions run there. Epupp cannot run inside it, and agents cannot click in it.
- The two talk over `postMessage`. `pez.cellsify` speaks the same messages the app does, so from the REPL you can import a project, export it, reload the frame, and answer `:api-server` actions.
- A project is data: `:project/version`, `:project/meta`, and `:project/inputs`, a map from coord to input. Cellsify's export text is its serialization.
- Values come back to the editor through the **REPL cells**: a timer and the eval cell in the var sheet. `evaluate` queues a form, the timer fetches it, the eval cell evaluates it, and the next tick brings the value back.

## Sources of truth

Each kind of knowledge has one home. Read the one the work needs.

| Need | Read |
|------|------|
| The API: what each function takes and returns | `userscripts/pez/cellsify.cljs`, whole file, with its `(comment ...)` |
| A complete project built as data (toolbar, named refs, timer, canvas with pointer events) | `live-tampers/cellsify_life.cljs` |
| Names for things (user content frame, the app, input, coord, named ref, REPL cells, eval cell, ...) | `dev/vocabulary.md` |
| Writing formulas, controls, and actions | [references/formulas.md](./references/formulas.md) |
| Messages, export text, and how the user content frame behaves | [references/protocol.md](./references/protocol.md) |
| Epupp, Scittle, the FS API | the **epupp** skill when available, otherwise `docs/epupp-README.md` and `docs/repl-fs-sync.md` |

Use the vocabulary's words in code, docstrings, and talk. Before naming something new, check `dev/vocabulary.md`.

## Getting a REPL

Use the Backseat Driver Epupp session connected to the cellsify.com tab (`epupp` in PEZ's setup; `clojure_list_sessions` tells). The tool's `namespace` parameter does not switch the Epupp session's namespace, so evaluate `(in-ns 'user)` first, then call `pez.cellsify/...` fully qualified or `(require '[pez.cellsify :as c])`.

Every operation returns a promise of Clojure data. Return the promise from the evaluation and the tool shows the resolved value. Pass functions to `.then`: `(.then p :kw)` silently passes the value through unchanged.

## Build, push, verify

1. **Build the project as data.** Start from `new-project`, then `add-sheet`, `set-input` at a `coord`, with `formula` for formula inputs, `name-cell` for named refs, and `add-repl-cells` last so `evaluate` works. Keep builder functions pure and parameterized, the way `life-project` is.
2. **Reload, then push.** `(-> (reload-user-content!) (.then #(push! project)))`. Reloading stops every timer from earlier projects, including old REPL timers, and waits until the app has loaded its own project into the frame. A push made before that gets overwritten.
3. **Verify.**
   - `evaluate` returns the value of a form inside Cellsify, named refs included: `(evaluate '[running generation (count board)])`. Errors reject with Cellsify's own message.
   - `pull` returns the open project as data, including state that actions wrote. Actions write formula inputs, so a counter reads back as `"=13"`; `input-form` turns an input back into data.
   - `look` returns a screenshot of the user content frame as an image data URL.
4. **Iterate** by changing the builder and repeating step 2.

To change a project that already holds state worth keeping, pull, modify the data, and push it back. `push!` replaces whatever project is open, so ask PEZ before replacing a project you did not push yourself.

Clicks and pointer events need PEZ: ask him to click, then check the result with `evaluate` or `pull`. Timer actions run without help.

## What holds

- **Reload before every push.** Timers outlive their project on import and keep acting on the new one.
- **The open project carries REPL cells before you evaluate.** `evaluate` rejects with "No REPL cells answered" when they are missing or their timer has died; reload and push a project built with `add-repl-cells`. The REPL cells take X9Y10 and X10Y10 of the var sheet, so keep project cells off those two.
- **`evaluate` reads.** It answers one form per tick (100 ms). Forms travel as printed text, so they hold only readable data. State changes go through actions or `push!`.
- **Cells that other cells read stay error-free.** A formula reading an erroring cell errors too, and a timer that reads one stops for good. Recover with reload and push.
- **Large boards use one canvas.** Grid templates cost about 1 ms per cell per update, so a 100 by 100 grid crawls. One `:canvas` control with `:on-pointer-down` runs Life at 100 by 100 at full timer speed.
- **Collections are formula inputs.** Plain inputs parse only scalars; `"=#{[1 2]}"` holds a set, `"#{[1 2]}"` does not.
- **Scittle is not full ClojureScript.** It has no `swap-vals!`, for example. Check in the REPL before relying on a core function.

## Secrets

The app's IndexedDB holds PEZ's OpenAI API key in plain text, in `system-settings`. Never read, print, export, or capture it, and leave the app's IndexedDB and localForage storage alone. Everything `pez.cellsify` needs travels as messages.

## Changing the library

`userscripts/pez/cellsify.cljs` is the source. After editing it:

1. Load the file in the REPL to update the running library. `!wire` is a `defonce` and `listen!` replaces its own message listener, so reloading the file keeps pending requests and does not double the listener.
2. Save it to Epupp so the next page load gets it. With REPL FS Sync enabled for the tab, evaluate `(epupp.fs/save! "<the whole file text>" {:fs/force? true})`, where the string literal is the file's text (Scittle in the page cannot read local files). The `bb upload` and `bb diff` tasks in `userscripts/` (see `userscripts/README.md`) wrap the same FS API when they can reach the tab.
3. Confirm with `(epupp.fs/show "pez/cellsify.cljs")` that the saved text matches the file.

Keep the docstrings and the `(comment ...)` at the bottom current: the RCF is where usage examples live.

## Recording discoveries

The message protocol is alpha and undocumented. Treat it as our API and expect to redo things when Cellsify changes. When you find something new, record it where it belongs:

- Cellsify behaviour, messages, formula language: `references/protocol.md` or `references/formulas.md` in this skill.
- A new name: `dev/vocabulary.md`, through the corpus rule in its header.
- How to call a function: its docstring and the library's RCF.
