(ns gist-sync
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]))

(def ^:private gist-set-path "gist_sync.edn")

(def ^:private gist-id-re #"^[0-9a-fA-F]{32}$")

(def ^:private bookkeeping-line-re
  #"\n :epupp/gist \"[0-9a-fA-F]{32}\"|\n :epupp/gist-sync \"[0-9a-fA-F]{40}\"")

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

(defn- strip-bookkeeping
  "Drop gist id and sync revision lines. Those stay on the local script."
  [code]
  (string/replace (or code "") bookkeeping-line-re ""))

(defn- first-map-end
  "Index of the closing brace of the first map form."
  [code]
  (let [start (string/index-of code "{")]
    (when-not start
      (abort! "Script has no manifest map."))
    (loop [i start depth 0 in-str? false escape? false]
      (if (>= i (count code))
        (abort! "Script manifest is not closed.")
        (let [c (nth code i)]
          (cond
            escape? (recur (inc i) depth in-str? false)
            (and in-str? (= c \\)) (recur (inc i) depth true true)
            (and in-str? (= c \")) (recur (inc i) depth false false)
            in-str? (recur (inc i) depth true false)
            (= c \") (recur (inc i) depth true false)
            (= c \{) (recur (inc i) (inc depth) false false)
            (= c \}) (if (= depth 1)
                       i
                       (recur (inc i) (dec depth) false false))
            :else (recur (inc i) depth false false)))))))

(defn- manifest-entry-pattern
  [k]
  (re-pattern (str "(?m)^[ ]*"
                   (java.util.regex.Pattern/quote (pr-str k))
                   "\\s+\"[^\"]*\"")))

(defn- upsert-manifest-entry
  "Set a string entry in the manifest map."
  [code k v]
  (let [line (str " " (pr-str k) " " (pr-str v))
        pattern (manifest-entry-pattern k)]
    (if (re-find pattern code)
      (string/replace code pattern line)
      (let [end (first-map-end code)]
        (str (subs code 0 end) "\n" line (subs code end))))))

(defn- local-record
  "Shared source plus the local gist id and the revision it was synced at."
  [code gist-id rev]
  (-> code
      strip-bookkeeping
      (upsert-manifest-entry :epupp/gist gist-id)
      (upsert-manifest-entry :epupp/gist-sync rev)))

(defn- read-gist-ids
  []
  (if (fs/exists? gist-set-path)
    (vec (edn/read-string (slurp gist-set-path)))
    []))

(defn- write-gist-ids!
  [ids]
  (spit gist-set-path
        (str "["
             (string/join "\n " (map pr-str ids))
             "]\n")))

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
                    :script/gist-id (:epupp/gist manifest)
                    :script/synced-rev (:epupp/gist-sync manifest)
                    :script/description (or (:epupp/description manifest) "")}))))
       (filterv some?)))

(defn- pair-set
  "Pair listed gists with local scripts. Gist id in the manifest wins, then script name."
  [scripts gists]
  (let [{:keys [assigned used]}
        (reduce (fn [{:keys [used] :as acc} gist]
                  (let [by-id (some (fn [script]
                                      (when (= (:script/gist-id script) (:gist/id gist))
                                        script))
                                    scripts)
                        by-name (some (fn [script]
                                        (when (and (= (:script/script-name script)
                                                      (:gist/script-name gist))
                                                   (not (used (:script/path script))))
                                          script))
                                      scripts)
                        script (or by-id by-name)]
                    {:assigned (conj (:assigned acc) {:gist gist :script script})
                     :used (cond-> used script (conj (:script/path script)))}))
                {:assigned [] :used #{}}
                gists)
        script-only (remove (fn [script] (used (:script/path script))) scripts)]
    {:pairs assigned
     :script-only (vec script-only)}))

(defn- relation
  "Classify one pair against the remembered gist revision, when the script has one."
  [{:keys [gist script]}]
  (cond
    (nil? script) :gist-only
    (nil? gist) :script-only
    :else
    (let [local (strip-bookkeeping (:script/code script))
          remote (strip-bookkeeping (:gist/code gist))
          remembered (:script/synced-rev script)]
      (cond
        (= local remote) :same
        (nil? remembered) :unrecorded
        (= (:gist/rev gist) remembered) :script-differs
        :else (let [old (strip-bookkeeping (:gist/code (fetch-gist-rev (:gist/id gist) remembered)))]
                (if (= local old)
                  :gist-differs
                  :both))))))

(defn- load-world
  []
  (let [scripts (local-scripts)
        ids (->> (concat (read-gist-ids)
                         (keep :script/gist-id scripts))
                 distinct
                 vec)
        gists (mapv fetch-gist ids)]
    (pair-set scripts gists)))

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
          remote (strip-bookkeeping (:gist/code gist))
          local (strip-bookkeeping (:script/code script))]
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

(defn- remember!
  [ids gist-id]
  (when-not (some #{gist-id} ids)
    (write-gist-ids! (conj (vec ids) gist-id))))

(defn- push!
  [{:keys [gist script]} private?]
  (when-not script
    (abort! "Nothing to push. Pass a userscript path to create a gist."))
  (let [ids (read-gist-ids)
        source (strip-bookkeeping (:script/code script))
        filename (gist-filename script gist)
        create? (nil? gist)
        uploaded (if (and gist (= source (strip-bookkeeping (:gist/code gist))))
                   gist
                   (write-gist! (:gist/id gist) filename source (not private?) create?))
        gist-id (:gist/id uploaded)]
    (write-script! (:script/path script) (local-record source gist-id (:gist/rev uploaded)))
    (remember! ids gist-id)
    (println (str "  pushed " (:script/path script) " -> " gist-id))))

(defn- pull!
  [{:keys [gist script]}]
  (when-not gist
    (abort! "Nothing to pull. That script has no gist."))
  (let [ids (read-gist-ids)
        gist-id (:gist/id gist)
        source (strip-bookkeeping (:gist/code gist))
        path (or (:script/path script) (:gist/script-name gist))]
    (when-not path
      (abort! (str "Gist " gist-id " has no :epupp/script-name to pull into.")))
    (write-script! path (local-record source gist-id (:gist/rev gist)))
    (remember! ids gist-id)
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
