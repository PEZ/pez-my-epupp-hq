# Messages, export text, and the user content frame

How `pez.cellsify` talks to Cellsify, and how the user content frame behaves. Reverse engineered; Cellsify does not document it. Read this before changing the wire, the export text code, or `reload-user-content!`.

## The frames

- The app: `https://cellsify.com/app/`. Menus and project navigation. All Epupp injection happens in this top frame.
- The user content frame: `https://cellsify-usercontent.com/xA9b4Zo03yX89e/cells-user-content.html`. Grid, inspector, and the SCI evaluator for formulas.
- `user-content-window` reads `contentWindow` through the `HTMLIFrameElement` prototype getter. Compare message sources with `identical?`; `=` throws on a cross-origin window and the message is silently dropped.
- The user content frame's JavaScript bundle can be fetched with curl. The app's bundle needs the browser's login, so fetch it from the REPL.

## Message text

Both directions use the same message text: the printed message, the correlation id, and the extra, one per line.

```
{:fn-id :import-project}
900017
<export text>
```

- A fire-and-forget message leaves the correlation id empty.
- A reply carries the request's correlation id. The extra is the raw payload text.
- `pez.cellsify` numbers its requests from 900000 up, clear of the app's own counter.

Messages seen from the app to the user content frame:

| `:fn-id` | What it does |
|----------|--------------|
| `:import-project` | Replaces the open project. The extra is export text. |
| `:request-project-export` | Replies `:request-project-export-response` with export text in the extra. |
| `:switch-to-sheet-by-key` | `{:sheet-id 1 :keys-pressed #{}}` makes that sheet active. |
| `:load-project`, `:eval-all-cells`, `:save-project` | Seen in the app's traffic; not used by the library yet. |
| `:local-server-request-response` | The answer to a `:local-server-request`: `{:ok value}` or `{:err message}`. |

Messages seen from the user content frame to the app:

| `:fn-id` | When |
|----------|------|
| `:sheets-updated` | The frame sends it after it starts. `reload-user-content!` waits for the first one after a reload. |
| `:local-server-request` | An `:api-server` action fired. `:request` holds the action's request value. The app has no handler of its own; `pez.cellsify` answers REPL cell requests itself and passes the rest to the `serve!` handler. |

## Export text

```
cellsify
[0 2 1]<record terminator>
{...project meta...}<record terminator>
[[[1 1]] :v 1]
=(+ 1 2 3)<record terminator>
...
```

- The record terminator is `"\n\n\u001e"`.
- The first record is the header with the project version, the second the project meta, then one record per input: the coord on the first line, the input after it.
- A coord is `[path layer sheet-id]`. Some exports carry a fourth element, a state key; `export-text` sorts by it too.
- Every project has the var sheet, sheet 0, a 10 by 10 grid that Cellsify keeps hidden. `new-project` includes it.

## How the user content frame behaves

- **Timers survive an import.** They keep ticking against the new project. Reloading the frame stops them.
- **The app loads its project late after a reload**, up to about 11 seconds, and overwrites anything imported meanwhile. The frame starts with an empty project named "New Project"; `reload-user-content!` polls `pull` until the name is something else.
- **Importing directly leaves the app's project navigation out of sync** with what the frame shows.
- **Grid templates cost about 1 ms per cell per update.**
- **Actions only fire from events**: clicks, pointer events, and timer ticks. Nothing outside the frame can click inside it.
- **A crash or a stuck frame** recovers with `reload-user-content!`.
