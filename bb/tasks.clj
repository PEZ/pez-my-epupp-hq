(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [sci.nrepl.browser-server :as bp]))

(defn browser-nrepl
  "Start browser nREPL"
  [{:keys [nrepl-port websocket-port]
    :or {nrepl-port 1339
         websocket-port 1340}}]
  (bp/start! {:nrepl-port nrepl-port :websocket-port websocket-port})
  (deref (promise)))

(def ^:private docs-sync-files
  {"docs/repl-fs-sync.md"        "repl-fs-sync.md"
   "docs/connecting-to-epupp.md" "connecting-to-epupp.md"
   "README.md"                   "epupp-README.md"})

(defn- sync-note [src]
  (str "> [!NOTE]\n"
       "> This file is synced from the [Epupp repository](https://github.com/PEZ/epupp)\n"
       "> (`" src "`).\n"
       "> To resync: `bb docs-sync`\n\n"))

(defn docs-sync
  "Sync docs from the epupp GitHub repository"
  []
  (let [api-url "https://api.github.com/repos/PEZ/epupp/contents/"
        docs-dir (str (fs/cwd) "/docs")]
    (fs/create-dirs docs-dir)
    (doseq [[src dest] docs-sync-files]
      (let [{:keys [status body]} (http/get (str api-url src)
                                            {:headers {"Accept" "application/vnd.github.raw+json"}
                                             :throw false})]
        (if (= 200 status)
          (do (spit (str docs-dir "/" dest) (str (sync-note src) body))
              (println "  ✓" src "->" dest))
          (println "  ✗" src "— HTTP" status))))
    (println "Done.")))

(comment
  (browser-nrepl {:nrepl-port 3339 :websocket-port 3340})
  (docs-sync)
  :rcf)