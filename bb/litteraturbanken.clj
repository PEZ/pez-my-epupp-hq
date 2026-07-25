(ns litteraturbanken
  "Download an OCR'd Litteraturbanken facsimile book, page by page, via the
   Epupp browser nREPL relay, into an EDN vector of {:page :text} maps.

   Inception: bb task --(nrepl client)--> relay :3339 --(websocket)--> browser
   Scittle REPL in the Litteraturbanken reader tab.

   The reader is an AngularJS SPA; navigation is client-side, so the browser
   REPL survives page changes. We drive Angular's $location to jump to a page,
   poll until it settles, then read the invisible OCR text layer (.overlay)
   and clean it.

   Browser code is written as quoted Clojure forms and serialized with `pr-str`
   at the edge (`browser-eval`), so it reads and edits like normal code."
  (:require [babashka.fs :as fs]
            [babashka.nrepl-client :as nrepl]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn browser-eval
  "Serialize a Scittle `form` with pr-str, send it through the relay to the
   browser, and read the EDN result."
  [port form]
  (let [{:keys [vals]} (nrepl/eval-expr {:host "127.0.0.1" :port port :expr (pr-str form)})]
    (some-> vals first edn/read-string)))

(defn clean-text
  "Collapse the OCR text layer's whitespace while preserving line breaks."
  [raw]
  (when raw
    (->> (str/split-lines raw)
         (map #(-> % (str/replace #"[\s\u00a0]+" " ") str/trim))
         (remove str/blank?)
         (str/join "\n"))))

(def ^:private nav-template
  "Scittle form that drives Angular's $location to page `PAGE` (book-agnostic,
   deriving the target from the current path), returning the new path."
  '(let [inj    (.injector (js/angular.element js/document.body))
         loc    (.get inj "$location")
         rs     (.get inj "$rootScope")
         target (.replace (.path loc) #"/sida/\d+/" (str "/sida/" PAGE "/"))]
     (.path loc target)
     (.$apply rs)
     target))

(defn- nav-form [n]
  (walk/postwalk-replace {'PAGE n} nav-template))

(def ^:private probe-form
  "Scittle form: current pager number, total, overlay length + fingerprint."
  '(let [pt (.-textContent (js/document.querySelector ".pages"))
         ov (js/document.querySelector ".overlay")
         t  (if ov (.-textContent ov) "")]
     {:page  (some-> (re-find #"^\d+" (or pt "")) js/parseInt)
      :total (some-> (re-find #"\d+$" (or pt "")) js/parseInt)
      :len   (count t)
      :fp    (subs t 0 (min 60 (count t)))}))

(def ^:private read-form
  '(.-textContent (js/document.querySelector ".overlay")))

(defn fetch-page!
  "Navigate the browser to page n, wait until it settles, return {:page :total :text}.
   Settled = pager shows n AND overlay fingerprint stable across two probes."
  [port n {:keys [poll-ms max-polls] :or {poll-ms 150 max-polls 60}}]
  (browser-eval port (nav-form n))
  (loop [i 0 prev nil]
    (Thread/sleep poll-ms)
    (let [{:keys [page total len fp] :as cur} (browser-eval port probe-form)
          settled? (and (= page n) prev (= (:len prev) len) (= (:fp prev) fp))]
      (cond
        settled? {:page n :total total :text (clean-text (browser-eval port read-form))}
        (>= i max-polls) {:page n :total total :timeout? true
                          :text (clean-text (browser-eval port read-form))}
        :else (recur (inc i) cur)))))

(defn- write-edn! [out data]
  (some-> (fs/parent out) fs/create-dirs)
  (spit out (with-out-str (pp/pprint data))))

(defn download-book!
  "Loop pages start..end, collecting {:page :text}, checkpointing every `every`
   pages, writing the final EDN vector to `out`. Returns {:pages :out}."
  [{:keys [port start end out every]
    :or {port 3339 start 1 every 20 out "data/book.edn"}}]
  (let [{:keys [total]} (browser-eval port probe-form)
        end (or end total)]
    (println (format "Downloading pages %d\u2013%d of %d \u2192 %s" start end total out))
    (loop [n start acc []]
      (if (> n end)
        (do (write-edn! out acc)
            (println (format "Done. %d pages \u2192 %s" (count acc) out))
            {:pages (count acc) :out out})
        (let [{:keys [text timeout?]} (fetch-page! port n {})
              acc' (conj acc {:page n :text text})]
          (println (format "  page %d/%d  %d chars%s" n end (count text)
                           (if timeout? "  \u26a0 TIMEOUT" "")))
          (when (zero? (mod n every)) (write-edn! out acc'))
          (recur (inc n) acc'))))))

(defn download
  "Task entry: parse CLI opts and download."
  [opts]
  (download-book! opts))
