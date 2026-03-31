# PEZ's Epupp HQ

This is PEZ's personal workspace for all Epupp usage — live tampering, userscript development, and building up site-specific helpers over time. It is a living project that grows with use.

## Dual-Repo Workspace

This workspace contains two repos side by side:

- **pez-my-epupp-hq** ("this project", "my epupp hq") — PEZ's personal Epupp workspace. Userscripts, live tampers, site-specific helpers, and personal configuration live here.
- **original-my-epupp-hq** ("the template", "the original") — The upstream [my-epupp-hq](https://github.com/PEZ/my-epupp-hq) template repo. Changes here are contributions back to the community template intended for all Epupp users.

When working in files under `original-my-epupp-hq/`, treat all changes as upstream contributions: keep them general, avoid PEZ-specific content, and maintain the template's documentation voice (instructional, welcoming to new users).

When working in `pez-my-epupp-hq/`, this is personal space — be direct, opinionated, and build on accumulated context.

## AGENTS.md Is Disabled

The workspace setting `chat.useAgentsMdFile: false` means `AGENTS.md` is not loaded as workspace instructions. It exists as part of the template (for dog-fooding) but this `copilot-instructions.md` replaces its role. Do not reference `AGENTS.md` for Epupp knowledge — use `docs/epupp-README.md` and the sections below instead.

## Epupp Essentials

Epupp is a browser extension that runs **Scittle** (SCI — interpreted ClojureScript) inside web pages and exposes a REPL over nREPL via a `bb browser-nrepl` relay.

**Required reading**: `docs/epupp-README.md` contains the full Epupp documentation — userscript anatomy, manifest keys, available Scittle libraries, script timing, FS API, connection setup, settings, and troubleshooting. Read it before acting on Epupp tasks.

### Runtime constraints

- Scittle (SCI in the browser) — not standard ClojureScript, not Node.js, not JVM
- Direct DOM access via `js/` interop
- Only bundled Scittle libraries available (no npm, see docs for the library table)
- `^:async` functions + `await` for async work (no top-level `await`)
- State persists across REPL evals within a page, resets on reload
- Userscripts are self-contained — no cross-script imports

### Key pitfalls

- **Navigation tears down the REPL**: Wrap `set! js/window.location` in `js/setTimeout` so the eval response returns first
- **Clipboard blocked on many sites**: Use textarea + `execCommand "copy"` workaround (works from click handlers, not raw REPL eval)
- **Return data, don't print it**: `prn`/`println` output may not be captured by REPL tooling — return values directly
- **Don't use `epupp/` prefix** in script names — reserved for built-in scripts
- **Don't assume DOM at `document-start`** — `document.body` is null

### FS API (when REPL is connected)

Read operations always work; write operations require FS REPL Sync enabled in Epupp settings.

```clojure
;; Read
(epupp.fs/ls)
(epupp.fs/show "script.cljs")

;; Write (FS Sync required)
(epupp.fs/save! code-string)
(epupp.fs/save! code-string {:fs/force? true})
(epupp.fs/mv! "old.cljs" "new.cljs")
(epupp.fs/rm! "script.cljs")

;; Load libraries at REPL time
(epupp.repl/manifest! {:epupp/inject ["scittle://replicant.js"]})
```

## Project Structure and Conventions

```
pez-my-epupp-hq/
  docs/               # Synced from Epupp repo (bb docs-sync)
  userscripts/         # Scripts with manifests, synced to Epupp (bb tasks in this dir)
  live-tampers/        # REPL-evaluated code — experiments, site helpers
    <site>/            # Per-site subdirectory (github/, youtube/, etc.)
      helpers.cljs     # Accumulated helper functions for that site
  snippets/            # Reusable hiccup/code snippets
```

### Per-site live-tampers convention

Each preconfigured site relay gets a subdirectory under `live-tampers/` (e.g., `live-tampers/github/`, `live-tampers/youtube/`). Site-specific helper functions accumulate in `helpers.cljs` within each subdirectory. This convention is worth propagating back to the template as it matures.

### Multi-REPL sessions

| Session | nREPL Port | WebSocket Port | Domain |
|---------|-----------|----------------|--------|
| `epupp-default` | 3339 | 3340 | General |
| `epupp-github` | 11331 | 11332 | GitHub |
| `epupp-gitlab` | 11333 | 11334 | GitLab |
| `epupp-youtube` | 11335 | 11336 | YouTube |
| `epupp-ebay` | 23398 | 23399 | eBay |

## Clojure Principles

Principle #1: Always load the Clojure skill. (**OO**DA)

Data-oriented, functional, REPL-first. Evaluate first, commit to code second.

- Verify assumptions via REPL — it is the source of truth
- Functional core, imperative shell — side effects only at edges
- Prefer pure data transformations over atoms; minimize `swap!`/`reset!`
