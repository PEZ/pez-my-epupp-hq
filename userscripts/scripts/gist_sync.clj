(ns gist-sync
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]))

(def ^:private gist-set-path "gist_sync.edn")

(def ^:private gist-id-re #"^[0-9a-fA-F]{32}$")

(defn parse-cli
  "Parse flags anywhere among positional args."
  [args]
  (loop [remaining args flags [] positional []]
    (if (empty? remaining)
      (cli/parse-args (concat flags positional)
                      {:coerce {:push :boolean
                                :pull :boolean
                                :private :boolean}})
      (let [[arg & more] remaining]
        (if (#{"--push" "--pull" "--private"} arg)
          (recur more (conj flags arg) positional)
          (recur more flags (conj positional arg)))))))

(defn- abort!
  [message]
  (binding [*out* *err*]
    (println message))
  (throw (ex-info "" {:babashka/exit 1})))

(defn- read-manifest
  [code]
  (try
    (let [form (edn/read-string code)]
      (when (and (map? form) (:epupp/script-name form))
        form))
    (catch Exception _ nil)))

(defn- read-set
  "Read gist id, script path, and last synced revision. A bare id string still counts."
  []
  (if (fs/exists? gist-set-path)
    (mapv (fn [entry]
            (if (string? entry)
              {:gist/id entry}
              entry))
          (edn/read-string (slurp gist-set-path)))
    []))

(defn- write-set!
  [entries]
  (spit gist-set-path
        (str "[\n"
             (string/join "\n" (map #(str " " (pr-str %)) entries))
             "\n]\n")))

(defn- record-sync!
  "Remember a gist id, the local path, and the revision those two matched."
  [entries gist-id path rev]
  (let [next-entry (cond-> {:gist/id gist-id}
                     path (assoc :script/path path)
                     rev (assoc :gist/rev rev))
        known? (some #(= gist-id (:gist/id %)) entries)
        entries (if known?
                  (mapv (fn [entry]
                          (if (= gist-id (:gist/id entry))
                            next-entry
                            entry))
                        entries)
                  (conj entries next-entry))]
    (write-set! entries)))

(defn- gh-json
  [method path body]
  (let [opts {:out :string
              :err :string
              :continue true}
        opts (if body (assoc opts :in body) opts)
        args (cond-> ["gh" "api" "--method" method path]
               body (concat ["--input" "-"]))
        {:keys [exit out err]} (apply process/shell opts args)]
    (if (zero? exit)
      (json/parse-string out false)
      (abort! (str "gh api " method " " path " failed.\n" err)))))

(defn- file-content
  [file]
  (if (get file "truncated")
    (let [{:keys [exit out err]} (process/shell {:out :string
                                                 :err :string
                                                 :continue true}
                                                "gh" "api"
                                                (get file "raw_url")
                                                "-H" "Accept: application/vnd.github.raw")]
      (if (zero? exit)
        out
        (abort! (str "Failed to read gist file " (get file "filename") ".\n" err))))
    (get file "content")))

(defn- coerce-gist
  [raw]
  (let [file (first (vals (get raw "files")))
        code (file-content file)
        manifest (read-manifest code)]
    {:gist/id (get raw "id")
     :gist/rev (get (first (get raw "history")) "version")
     :gist/filename (get file "filename")
     :gist/code code
     :gist/script-name (:epupp/script-name manifest)
     :gist/description (or (:epupp/description manifest)
                           (:epupp/script-name manifest)
                           "")}))

(defn- fetch-gist
  [gist-id]
  (coerce-gist (gh-json "GET" (str "/gists/" gist-id) nil)))

(defn- fetch-gist-rev
  [gist-id rev]
  (coerce-gist (gh-json "GET" (str "/gists/" gist-id "/" rev) nil)))

(defn- local-scripts
  []
  (->> (fs/glob "." "**.cljs")
       (mapv (fn [path]
               (let [rel (str (fs/relativize "." path))
                     code (slurp (fs/file path))
                     manifest (read-manifest code)]
                 (when manifest
                   {:script/path rel
                    :script/code code
                    :script/script-name (:epupp/script-name manifest)
                    :script/description (or (:epupp/description manifest) "")}))))
       (filterv some?)))

(defn- pair-set
  "Pair listed gists with local scripts. A path in the set wins, then script name."
  [scripts gists entries]
  (let [entry-by-id (into {} (map (juxt :gist/id identity) entries))
        script-by-path (into {} (map (juxt :script/path identity) scripts))
        {:keys [assigned used]}
        (reduce (fn [{:keys [used] :as acc} gist]
                  (let [entry (entry-by-id (:gist/id gist))
                        by-path (script-by-path (:script/path entry))
                        by-name (some (fn [script]
                                        (when (and (= (:script/script-name script)
                                                      (:gist/script-name gist))
                                                   (not (used (:script/path script))))
                                          script))
                                      scripts)
                        script (or by-path by-name)]
                    {:assigned (conj (:assigned acc)
                                     {:gist gist
                                      :script script
                                      :sync/rev (:gist/rev entry)})
                     :used (cond-> used script (conj (:script/path script)))}))
                {:assigned [] :used #{}}
                gists)
        script-only (remove (fn [script] (used (:script/path script))) scripts)]
    {:pairs assigned
     :script-only (vec script-only)}))

(defn- relation
  "Classify one pair against the revision remembered in gist_sync.edn."
  [{:keys [gist script] :sync/keys [rev]}]
  (cond
    (nil? script) :gist-only
    (nil? gist) :script-only
    :else
    (let [local (:script/code script)
          remote (:gist/code gist)]
      (cond
        (= local remote) :same
        (nil? rev) :unrecorded
        (= (:gist/rev gist) rev) :script-differs
        :else (let [old (:gist/code (fetch-gist-rev (:gist/id gist) rev))]
                (if (= local old)
                  :gist-differs
                  :both))))))

(defn- load-world
  []
  (let [entries (read-set)
        scripts (local-scripts)
        gists (mapv fetch-gist (mapv :gist/id entries))]
    (pair-set scripts gists entries)))

(defn- pair-label
  [{:keys [gist script]}]
  (str (or (:script/path script) (:gist/script-name gist))
       "  "
       (:gist/id gist)))

(defn- print-section
  [title rows]
  (println (str title " (" (count rows) ")"))
  (if (seq rows)
    (doseq [row rows]
      (println (str "  " row)))
    (println "  (none)"))
  (println))

(defn- status!
  []
  (let [{:keys [pairs script-only]} (load-world)
        classified (mapv (fn [pair] (assoc pair :relation (relation pair))) pairs)
        of-kind (fn [kind] (filterv #(= kind (:relation %)) classified))
        both (of-kind :both)]
    (print-section "Script differs from gist"
                   (map pair-label (concat (of-kind :script-differs) both)))
    (print-section "Gist differs from script"
                   (map pair-label (concat (of-kind :gist-differs) both)))
    (print-section "Differ, no recorded sync"
                   (map pair-label (of-kind :unrecorded)))
    (print-section "Gist only"
                   (map pair-label (of-kind :gist-only)))
    (print-section "Script only"
                   (map :script/path script-only))
    (println (str (count (of-kind :same)) " same"))))

(defn- normalize-path
  [arg]
  (str (fs/relativize "." (fs/path arg))))

(defn- find-pair
  [arg world]
  (let [{:keys [pairs script-only]} world]
    (cond
      (and (fs/exists? arg) (fs/regular-file? arg))
      (let [path (normalize-path arg)
            paired (some (fn [pair]
                           (when (= path (:script/path (:script pair)))
                             pair))
                         pairs)
            lone (some (fn [script]
                         (when (= path (:script/path script))
                           {:gist nil :script script}))
                       script-only)]
        (or paired lone))

      (re-matches gist-id-re arg)
      (let [gist-id (string/lower-case arg)]
        (some (fn [pair]
                (when (= gist-id (:gist/id (:gist pair)))
                  pair))
              pairs))

      :else nil)))

(defn- run-diff!
  [gist script]
  (fs/with-temp-dir [tmp {}]
    (let [remote-file (str (fs/path tmp "gist"))
          local-file (str (fs/path tmp "script"))
          remote (:gist/code gist)
          local (:script/code script)]
      (spit remote-file remote)
      (spit local-file local)
      (let [{:keys [out]} (process/shell {:out :string :continue true}
                                         "diff" "-u"
                                         "-L" (str "gist://" (:gist/id gist)
                                                   "/" (:gist/filename gist))
                                         "-L" (:script/path script)
                                         remote-file local-file)]
        (print out)))))

(defn- gist-filename
  [script gist]
  (or (:gist/filename gist)
      (fs/file-name (:script/path script))))

(defn- write-gist!
  [gist-id filename content public? create?]
  (let [body (json/generate-string
              (cond-> {"files" {filename {"content" content}}}
                create? (assoc "public" public?
                               "description" (or (:epupp/description (read-manifest content))
                                                 filename))))
        path (if create? "/gists" (str "/gists/" gist-id))]
    (coerce-gist (gh-json (if create? "POST" "PATCH") path body))))

(defn- write-script!
  [path code]
  (fs/create-dirs (fs/parent (fs/path path)))
  (spit path code))

(defn- push!
  [{:keys [gist script]} private?]
  (when-not script
    (abort! "Nothing to push. Pass a userscript path to create a gist."))
  (let [source (:script/code script)
        filename (gist-filename script gist)
        create? (nil? gist)
        uploaded (if (and gist (= source (:gist/code gist)))
                   gist
                   (write-gist! (:gist/id gist) filename source (not private?) create?))]
    (record-sync! (read-set) (:gist/id uploaded) (:script/path script) (:gist/rev uploaded))
    (println (str "  pushed " (:script/path script) " -> " (:gist/id uploaded)))))

(defn- pull!
  [{:keys [gist script]}]
  (when-not gist
    (abort! "Nothing to pull. That script has no gist."))
  (let [gist-id (:gist/id gist)
        path (or (:script/path script) (:gist/script-name gist))]
    (when-not path
      (abort! (str "Gist " gist-id " has no :epupp/script-name to pull into.")))
    (write-script! path (:gist/code gist))
    (record-sync! (read-set) gist-id path (:gist/rev gist))
    (println (str "  pulled " gist-id " -> " path))))

(defn- diff!
  [{:keys [gist script]}]
  (cond
    (nil? gist) (abort! (str "No gist for " (:script/path script) "."))
    (nil? script) (abort! (str "No userscript for gist " (:gist/id gist) "."))
    :else (run-diff! gist script)))

(defn exec!
  "Report gist drift, diff one pair, or push or pull that pair."
  [{:keys [args opts]}]
  (cond
    (> (count args) 1)
    (abort! "Pass one path or one gist sha.")

    (and (:push opts) (:pull opts))
    (abort! "Pass either --push or --pull.")

    (and (or (:push opts) (:pull opts))
         (empty? args))
    (abort! "Pass a path or a gist sha to sync.")

    (empty? args)
    (status!)

    :else
    (let [world (load-world)
          pair (find-pair (first args) world)]
      (when-not pair
        (abort! (str "Not found: " (first args))))
      (cond
        (:push opts) (push! pair (:private opts))
        (:pull opts) (pull! pair)
        :else (diff! pair)))))
