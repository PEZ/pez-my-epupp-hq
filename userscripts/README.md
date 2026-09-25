# Userscripts

Your Epupp userscripts live here. Each script is a `.cljs` file with a manifest map as the first form, followed by the script code.
* See [epupp-README.md](../docs/epupp-README.md) for the manifest format and available Scittle libraries.
* See [repl-fs-sync.md](../docs/repl-fs-sync.md) for details on how the REPL filesystem sync works, which these scripts rely on.

## Syncing Scripts with Epupp

This directory is also a self-contained [Babashka](https://github.com/babashka/babashka) project with tasks for syncing scripts between your local files and the Epupp browser extension storage. This lets you keep your scripts in version control and push/pull them to/from the browser.

All commands are run from the `userscripts/` directory and require:

1. The browser-nrepl relay running (start it from the project root with `bb browser-nrepl`)
2. A browser tab connected to Epupp
3. **FS REPL Sync** enabled in Epupp extension settings

### `bb ls`

List scripts stored in Epupp. Built-in `epupp/` scripts are skipped.

```sh
bb ls                                # list all scripts
bb ls pez                            # list scripts under pez/
bb ls --port 11331                   # list scripts on a different port
```

### `bb download`

Download scripts from Epupp to local files. Built-in `epupp/` scripts are skipped.

```sh
bb download                          # all scripts
bb download pez                      # all scripts under pez/
bb download hq/hello_world.cljs      # specific script
bb download --force                  # overwrite existing local files
bb download --dry-run                # show what would happen
bb download --port 11331             # use a different relay port
```

### `bb upload`

Upload local scripts to Epupp. Scripts without a valid manifest are skipped with a warning.

```sh
bb upload                            # all scripts
bb upload hq                         # all scripts under hq/
bb upload hq/hello_world.cljs        # specific script
bb upload --force                    # overwrite existing scripts in Epupp
bb upload --dry-run                  # show what would happen
```

### `bb diff`

Compare local scripts against what's stored in Epupp. Produces unified diff output for scripts that differ, and a summary of identical, different, remote-only, and local-only scripts.

```sh
bb diff                              # all scripts
bb diff pez                          # all scripts under pez/
bb diff hq/hello_world.cljs          # specific script
```

### `bb gist-sync`

Compare userscripts with the gists listed in `gist_sync.edn`. With no arguments, print which script differs from its gist, which gist differs from its script, gists with no script, and scripts with no gist. One argument prints a diff. Add `--push` or `--pull` to write that pair. `--private` makes a newly created gist secret.

A path that exists is a userscript. A 32-character hex string is a gist id. Anything else is not found.

Push and pull record the gist id, the script path, and the gist revision in `gist_sync.edn`. The userscript file is the same text in git, in the gist, and in Epupp. `--pull` writes the local file and does not change the gist.

```sh
bb gist-sync
bb gist-sync pez/element_printing.cljs
bb gist-sync 3b0fdc406e7593eaaef609b6fb4a687d
bb gist-sync pez/element_printing.cljs --push
bb gist-sync 3f499d088a742386c5a42761c6c06c5a --pull
bb gist-sync pez/page_choices.cljs --push --private
```

### Path Arguments

Path arguments are relative to `userscripts/`. Any argument not ending in `.cljs` is treated as a directory prefix and expanded to all matching scripts. So `bb diff pez` and `bb diff pez/` both work.

### Ports

All commands default to port `3339`. Use `--port` to target a relay running on a different port, matching your per-domain port configuration in Epupp.
