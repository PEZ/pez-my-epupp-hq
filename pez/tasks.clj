(ns tasks
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn- exclude?
  "Paths to ignore when comparing repos."
  [p]
  (or (re-find #"(^|/)\.git/" p)
      (= p ".git")
      (re-find #"(^|/)\.calva/" p)
      (re-find #"(^|/)\.clj-kondo/" p)
      (re-find #"(^|/)\.lsp/" p)
      (str/starts-with? p ".cpcache/")
      (str/starts-with? p ".portal/")
      (str/starts-with? p "pez/")
      (= p ".DS_Store")
      (str/ends-with? p ".code-workspace")))

(defn- relative-paths [dir]
  (->> (fs/glob dir "**" {:hidden true})
       (filter fs/regular-file?)
       (map #(str (fs/relativize dir %)))
       (remove exclude?)
       (into (sorted-set))))

(defn compare-repos!
  "Compare files between personal project and template.
   Prints three groups: only in personal, only in template, differ."
  []
  (let [personal-dir (str (fs/cwd))
        template-dir (str (fs/path (fs/parent personal-dir) "my-epupp-hq"))
        personal-files (relative-paths personal-dir)
        template-files (relative-paths template-dir)
        only-personal (set/difference personal-files template-files)
        only-template (set/difference template-files personal-files)
        both (set/intersection personal-files template-files)
        differ (into (sorted-set)
                     (filter (fn [f]
                               (not= (slurp (str personal-dir "/" f))
                                     (slurp (str template-dir "/" f)))))
                     both)
        print-section (fn [header files]
                        (println (str "\n" header " (" (count files) ")"))
                        (if (seq files)
                          (doseq [f (sort files)]
                            (println (str "  " f)))
                          (println "  (none)")))]
    (println "Comparing personal project to template:")
    (println (str "  Personal: " personal-dir))
    (println (str "  Template: " template-dir))
    (print-section "Only in personal project" only-personal)
    (print-section "Only in template" only-template)
    (print-section "Differ" differ)))

(comment
  (compare-repos!)
  :rcf)
