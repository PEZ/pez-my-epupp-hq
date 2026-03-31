# My Epupp HQ

A workspace for live tampering the web from your editor and/or AI agent harness, using [Epupp](https://github.com/PEZ/epupp).

<div align="center">
  <a href="https://www.youtube.com/watch?v=CuEWN5yYVa8">
    <img src="https://img.youtube.com/vi/CuEWN5yYVa8/maxresdefault.jpg" alt="Epupp Demo Video">
  </a>
  <br>
  <sup><a href="https://www.youtube.com/watch?v=CuEWN5yYVa8">▶ Watch the demo on YouTube</a></sup>
</div>

## What is Epupp?

Epupp is a web browser extension, a bit similar to [Tampermonkey](https://www.tampermonkey.net/), that allows you to use userscripts to tamper with web pages you visit so that they behave as you want them. Unlike Tampermonkey, Epupp also starts a scripting REPL inside the page, exposing it to your editor and/or AI agent over the [nREPL](https://nrepl.org/) protocol. This lets you use your favorite tools to develop userscripts, and to modify/inspect web pages completely ad-hoc as you need it.

The scripting environment in Epupp is [Scittle](https://github.com/babashka/scittle), which provides an interpreted version of [ClojureScript](https://clojurescript.org/). This is a very dynamic programming language, enabling full Interactive Programming. If you have ever configured/scripted Emacs, you will recognize the model.

Let's name the “editor and/or AI agent harness” as the “Epupp REPL client” or “REPL client” from now on.

To connect the REPL client to the browser tab (the REPL server) we use [browser-nrepl](https://github.com/babashka/sci.nrepl), a relay between the websocket exposed in the browser tab and the nREPL protocol spoken by the REPL client.

## What is My Epupp HQ?

This is a template repo aimed at providing a starting point and a hub for your live web tampering with Epupp. The workspace/project contains some basic configuration and instructions to guide you and your AI agents when adding Epupp to your daily routine.

## Prerequisites

* **Epupp** [installed](https://github.com/PEZ/epupp?tab=readme-ov-file#install) in your browser
* **Babashka** [installed](https://github.com/babashka/babashka#installation) on your computer
* An **Epupp REPL client** (a Clojure REPL enabled editor and/or AI harness)
* **Your copy of this repo** cloned to your computer
* At least a skim of the [docs/epupp-README.md](docs/epupp-README.md)

## Epupp Documentation

Key Epupp docs are synced from the [Epupp repository](https://github.com/PEZ/epupp) into the `docs/` folder in the project. To update them:

```sh
bb docs-sync
```

* [docs/epupp-README.md](docs/epupp-README.md) — The Epupp README
* [docs/connecting-to-epupp.md](docs/connecting-to-epupp.md) - How to connect editors and AIs to Epupp
* [docs/repl-fs-sync.md](docs/repl-fs-sync.md) — REPL filesystem sync documentation

## Start a Live Tamper Session

<div>

1. Start the browser-nrepl relay, from the project root (if you are using VS Code, see below for an alternative way to start the relay):
   ```sh
   bb browser-nrepl
   ```
2. Connect the browser tab to browser-nrepl: Click **Connect** in the Epupp extension's popup UI.
3. Connect your REPL client: This will depend on what you are using as your REPL client, see [docs/repl-fs-sync.md](docs/repl-fs-sync.md).
* If your REPL client is an editor:
   1. Open the file [userscripts/hq/hello_world.cljs](userscripts/hq/hello_world.cljs).
   2. Connect your editor to the REPL on port 1339
   3. Evaluate the file or the expression
* If your REPL client is an AI agent:
   1. Connect your AI agent harness to the REPL on port 1339
   2. Tell your agent that the Epupp REPL is connected and that you want it to quickly demo it for you.
</div>

## Sync the Hello World Script to Epupp

Your userscripts live in the `userscripts/` directory, ready to be synced to the Epupp extension via `bb` tasks. This requires FS REPL Sync to be enabled — see [docs/repl-fs-sync.md](docs/repl-fs-sync.md) for details.

With the browser-nrepl relay running and a tab connected to Epupp:

1. Enable **FS REPL Sync** in the Epupp popup Settings
2. From the `userscripts/` directory:
   ```sh
   bb upload hq/hello_world.cljs
   ```
3. Open the Epupp popup — the script should appear in the **Manual/on-demand** section (since it has no `:epupp/auto-run-match` pattern)
4. Click the **Play** button on the script to run it

To see what other sync commands are available:

```sh
bb tasks
```

See the [userscripts README](userscripts/README.md) for the full sync workflow.

## Explore example scripts

See [Epupp README](docs/epupp-README.md#epupp-userscript-gallery) for some links to example scripts.

## Enjoy! ♥️

Epupp is created and maintained by Peter Strömberg a.k.a PEZ, and provided as open source and is free to use. A lot of my time is spent on bringing Epupp and related software to you, and keeping it supported, working, and relevant.

* Please consider [sponsoring Epupp](https://github.com/sponsors/PEZ).
