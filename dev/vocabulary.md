# Vocabulary

Names in use in this workspace. Use these words in code, docstrings, comments, and talk. Before adding a new name, check here first. If nothing here fits, Language Guardian lands one term in this file.

## Epupp

- **Epupp** — the browser extension. It runs Scittle userscripts and gives the editor a REPL into a web page.
- **Scittle** — SCI in the browser. Epupp code is Scittle code, not standard ClojureScript.
- **live tamper** — changing a page from the editor through the REPL. Code for it lives in `live-tampers/`.
- **userscript** — a script that starts with a manifest. Userscripts live in `userscripts/`, PEZ's in `userscripts/pez/`.
- **manifest** — the map at the top of a userscript. Its keys are the **manifest keys** (`:epupp/script-name`, `:epupp/auto-run-match`, `:epupp/description`, `:epupp/run-at`, `:epupp/inject`, `:epupp/library?`).
- **script name** — `:epupp/script-name`. Epupp normalizes it to a path, `pez.cellsify` becomes `pez/cellsify.cljs`.
- **auto-run match** — `:epupp/auto-run-match`, the URL glob patterns a userscript runs on. A script without one runs only from the play button in the popup.
- **inject** — `:epupp/inject`, the dependency URLs Epupp loads before the script runs (`scittle://`, `epupp://`, CSS, pinned GitHub raw URLs).
- **library script** — a userscript other scripts inject with `epupp://`. `:epupp/library? true` lists it under Libraries in the popup.
- **built-in script** — a script that ships with Epupp, named `epupp/…`. Built-in libraries: `epupp.ui`, `epupp.storage`, `epupp.tools`.
- **popup** — the Epupp extension popup: REPL Connect, the script list, and Settings.
- **panel** — the Epupp tab in the browser's developer tools, for editing and evaluating scripts.
- **REPL** — the Epupp REPL in the connected tab. It evaluates Scittle code in the page.
- **browser-nrepl relay** — `bb browser-nrepl`. It relays between the REPL client and the connected tab.
- **REPL client** — the nREPL client in the editor or agent harness, such as Calva.
- **connect** — clicking **Connect** in the popup, which connects the tab to the browser-nrepl relay.
- **REPL FS Sync** — the per-tab setting "Allow REPL FS Sync for this tab". It turns on the write operations of `epupp.fs`.
- **FS API** — `epupp.fs`: `ls`, `show`, `save!`, `mv!`, `rm!`.
- **capture** — `epupp.tools` screenshots: `capture-element`, `capture-selector`, `capture-visible`.

## Cellsify

Cellsify is a Scittle spreadsheet at cellsify.com. `pez.cellsify` (`userscripts/pez/cellsify.cljs`) builds Cellsify projects from the editor.

### Cellsify's own words

- **the app** — the top frame at `cellsify.com/app`: menus and project navigation. Epupp and `pez.cellsify` run here.
- **user content frame** — the cross-origin frame on `cellsify-usercontent.com`. The grid lives there and formulas are evaluated there. The app and the user content frame talk over `postMessage`.
- **project list** — the app's list of saved projects, one card per project.
- **current project** — the project the app saves into, a name and an id the app keeps. It is the expanded card in the project list.
- **open project** — the project the user content frame has open. Push replaces it. It reaches the app's current project only when saved.
- **project** — what Cellsify opens, imports, and exports. In `pez.cellsify` a project is data: `:project/version`, `:project/meta`, `:project/inputs`.
- **sheet** — a top-level grid in a project, with a sheet id, a name, and a size in rows and columns.
- **var sheet** — sheet 0, named `var-sheet`. Cellsify keeps it hidden in every project.
- **cell** — one position in a grid. A cell can hold a nested grid.
- **input** — the text in one layer of one cell. A **plain input** is a scalar. A **formula input** starts with `=`.
- **formula** — an input that Cellsify evaluates. Formulas run sandboxed in `user`, with no `js/` interop.
- **coord** — `[path layer sheet-id]`, the key of an input.
- **path** — a vector of `[x y]`, one per nested grid. The empty path is the sheet itself.
- **layer** — `:v` is the value layer, `:f` is the format layer. The format layer holds `:control`, `:display`, and `:style`.
- **named ref** — a name for a coord, such as `user/board`. Formulas use the name to reach the cell.
- **control** — what a format layer makes a cell: `:grid`, `:button`, `:timer`, `:canvas`.
- **action** — one step in a control's event vector (`:on-click`, `:on-tick`, `:on-pointer-down`), such as `{:set …}` or `{:update …}`.
- **`:api-server` action** — an action that sends a `:local-server-request` message to the app and hands the reply to `:on-response`.
- **export text** — the text format Cellsify exports and imports: a header, the project meta, then one record per input.
- **message** — an EDN map with a `:fn-id`, such as `:import-project`, `:request-project-export`, or `:local-server-request`.
- **message text** — what goes over `postMessage`: the message, the correlation id, and the extra, one per line.
- **correlation id** — pairs a reply with its request. `pez.cellsify` uses ids from 900000 up.
- **extra** — the raw text after the correlation id, such as the export text.

### Words in `pez.cellsify`

- **wire** — the library's messages in flight: pending replies, watchers, and the local server handler.
- **request** — sending a message and getting a promise of the reply.
- **pull** — getting the open project from Cellsify as data.
- **push** — importing a project into the user content frame, replacing the open project. The app's project list does not see it, and a page reload loses it until it is saved.
- **create project** — making a new project in the app, the way its New Project button does (`:create-new-project`). The new project becomes the app's current project. `new-project` is different: it returns an empty project as data.
- **save** — clicking the app's save button, which stores the open project into the app's current project (`:save-project`). `save!` refuses unless both have the name it is given.
- **push new** — reload user content, create project, push, and save in one step, so the pushed project gets its own entry in the app's project list.
- **look** — a screenshot of the user content frame.
- **serve** — answering `:api-server` actions with a **local server handler**, a function of the request.
- **reload user content** — reloading the user content frame. It stops all timers and recovers from a crash, then waits for the app to load its project again.
- **REPL cells** — two cells in the var sheet that let the editor evaluate forms inside Cellsify: a timer at X9Y10 and the eval cell at X10Y10. Every tick the timer sends an `:api-server` request carrying the eval cell's value. The library answers with the next queued form as a formula input, and the timer's `:on-response` writes it into the eval cell. `add-repl-cells` returns a project with the REPL cells. `evaluate` takes a form and returns a promise of its value as evaluated in the user content frame. Named refs work in the form.
- **eval cell** — the REPL cell that holds the form being evaluated. The library writes a formula that calls `cellsify/eval` inside a `try`, and the eval cell's value becomes `[id [:ok value]]` or `[id [:err message]]`. The next tick sends that value back, which settles the promise from `evaluate`.
