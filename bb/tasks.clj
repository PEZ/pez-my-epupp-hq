(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
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

(defn- sync-note [src & [resync-cmd]]
  (let [cmd (or resync-cmd "bb docs-sync")]
    (str "> [!NOTE]\n"
         "> This file is synced from the [Epupp repository](https://github.com/PEZ/epupp)\n"
         "> (`" src "`).\n"
         "> To resync: `" cmd "`\n\n")))

(defn- cljs-sync-note [src resync-cmd]
  (str ";; NOTE: This file is synced from the Epupp repository\n"
       ";;   https://github.com/PEZ/epupp — `" src "`\n"
       ";;   To resync: `" resync-cmd "`\n\n"))

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

(defn exercises-sync
  "Sync exercise files from the epupp GitHub repository"
  []
  (let [api-url "https://api.github.com/repos/PEZ/epupp/contents/test-data/tampers"
        tampers-dir (str (fs/cwd) "/live-tampers")]
    (fs/create-dirs tampers-dir)
    (let [{:keys [status body]} (http/get api-url
                                          {:headers {"Accept" "application/json"}
                                           :throw false})]
      (if (= 200 status)
        (let [entries (json/parse-string body true)
              exercises (filter #(str/ends-with? (:name %) "_exercise.cljs") entries)]
          (if (seq exercises)
            (do
              (doseq [{:keys [name path]} exercises]
                (let [{dl-status :status dl-body :body}
                      (http/get (str api-url "/" name)
                                {:headers {"Accept" "application/vnd.github.raw+json"}
                                 :throw false})]
                  (if (= 200 dl-status)
                    (do (spit (str tampers-dir "/" name)
                              (str (cljs-sync-note path "bb exercises-sync") dl-body))
                        (println "  ✓" path "->" name))
                    (println "  ✗" path "— HTTP" dl-status))))
              (println "Done."))
            (println "No exercise files found.")))
        (println "  ✗ Failed to list tampers directory — HTTP" status)))))

(comment
  (browser-nrepl {:nrepl-port 3339 :websocket-port 3340})
  (docs-sync)
  :rcf)