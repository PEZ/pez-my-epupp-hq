{:epupp/script-name "pez/cellsify.cljs"
 :epupp/auto-run-match "https://cellsify.com/app/*"
 :epupp/description "Primitives for building Cellsify projects from the REPL"
 :epupp/inject ["epupp://epupp/tools.cljs"]}

(ns pez.cellsify
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [epupp.tools :as tools]))

;; Cellsify runs its sheets in a user content frame on another origin. The app
;; and that frame talk over postMessage. Epupp lives in the app frame, so these
;; primitives speak the same messages the app does.

;; Export text

(def record-terminator "\n\n\u001e")

(defn read-edn
  "Reads EDN, keeping unknown tagged literals as tagged literals."
  [s]
  (edn/read-string {:default tagged-literal} s))

(defn- parse-record [record]
  (let [i (or (str/index-of record "\n") (count record))]
    [(read-edn (subs record 0 i))
     (subs record (min (count record) (inc i)))]))

(defn parse-export
  "Returns the project in Cellsify export `text` as data."
  [text]
  (let [[header meta-text & records] (str/split text #"\n\n\u001e")]
    {:project/version (read-edn (second (str/split-lines header)))
     :project/meta (read-edn meta-text)
     :project/inputs (into {} (map parse-record) records)}))

(defn- input-order [[[path layer sheet-id state-key]]]
  [(str sheet-id) (count path) path layer (str state-key)])

(defn export-text
  "Returns Cellsify export text for `project`."
  [{:project/keys [version meta inputs]}]
  (->> (sort-by input-order inputs)
       (map (fn [[coord input]] (str (pr-str coord) "\n" input)))
       (concat [(str "cellsify\n" (pr-str version)) (pr-str meta)])
       (map #(str % record-terminator))
       str/join))

;; Projects as data

(defn coord
  "Returns the coord of `layer` (:v value, :f format) at `path` in sheet `sheet-id`.
   The path is a vector of [x y], one per nested grid. An empty path is the sheet."
  [sheet-id layer path]
  [path layer sheet-id])

(defn formula
  "Returns a formula input that evaluates `form`."
  [form]
  (str "=" (pr-str form)))

(defn- grid-format [rows columns]
  (formula {:control {:type :grid :rows rows :columns columns}}))

(defn new-project
  "Returns an empty project named `project-name`, with Cellsify's hidden var sheet."
  [project-name]
  {:project/version [0 2 1]
   :project/meta {:sheets-info {:sheets {0 {:sheet-id 0 :name "var-sheet" :x-count 10 :y-count 10
                                            :selection-info {:selection [[1 1]]}}}
                                :sheets-order []
                                :open-sheets []
                                :active-sheet nil}
                  :project {:name project-name}
                  :named-refs {:by-name {}}
                  :selection [[1 1]]}
   :project/inputs {(coord 0 :f []) (grid-format 10 10)}})

(defn add-sheet
  "Returns `project` with an open sheet of `rows` by `columns` cells. The first sheet becomes active."
  [project {:keys [sheet-id sheet-name rows columns]}]
  (-> project
      (assoc-in [:project/meta :sheets-info :sheets sheet-id]
                {:sheet-id sheet-id :name sheet-name :x-count columns :y-count rows
                 :selection-info {:selection [[1 1]]}})
      (update-in [:project/meta :sheets-info :sheets-order] conj sheet-id)
      (update-in [:project/meta :sheets-info :open-sheets] conj sheet-id)
      (update-in [:project/meta :sheets-info :active-sheet] #(or % sheet-id))
      (assoc-in [:project/inputs (coord sheet-id :f [])] (grid-format rows columns))))

(defn input-form
  "Returns the form of a formula input, or the text of a plain input."
  [input]
  (if (str/starts-with? input "=")
    (read-edn (subs input 1))
    input))

(defn set-input
  "Returns `project` with `input` at `coord`. A nil input removes it."
  [project coord input]
  (if (nil? input)
    (update project :project/inputs dissoc coord)
    (assoc-in project [:project/inputs coord] input)))

(defn name-cell
  "Returns `project` with the named ref user/`ref-name` pointing at `coord`."
  [project ref-name coord]
  (assoc-in project [:project/meta :named-refs :by-name (str "user/" ref-name)]
            {:coord coord :extra-var-from-coord nil :is-var nil}))

;; Messages

(def user-content-origin "https://cellsify-usercontent.com")

(defonce !wire (atom {:next-id 900000
                      :pending {}
                      :local-server-handler nil}))

(defn user-content-frame []
  (js/document.querySelector "iframe[src*='cellsify-usercontent']"))

(def ^:private content-window-getter
  (.-get (js/Object.getOwnPropertyDescriptor (.-prototype js/HTMLIFrameElement) "contentWindow")))

(defn user-content-window []
  (some->> (user-content-frame) (.call content-window-getter)))

(defn message-text
  "Returns the message text for `msg`: the EDN map, the correlation id, and the extra text, one per line."
  [msg correlation-id extra]
  (str (pr-str msg) "\n" correlation-id "\n" extra))

(defn parse-message
  "Returns {:message :correlation-id :extra} for wire `text`, or nil when it is not a Cellsify message."
  [text]
  (when (string? text)
    (let [i (str/index-of text "\n")
          j (some->> i inc (str/index-of text "\n"))]
      (when j
        {:message (read-edn (subs text 0 i))
         :correlation-id (subs text (inc i) j)
         :extra (subs text (inc j))}))))

(defn send!
  "Posts `msg` to the user content frame."
  ([msg] (send! msg nil nil))
  ([msg correlation-id extra]
   (.postMessage (user-content-window) (message-text msg correlation-id extra) user-content-origin)))

(defn- next-id! []
  (:next-id (swap! !wire update :next-id inc)))

(defn- expire!
  "Rejects with `message` after `timeout-ms` unless the entry at `path` in the wire is gone by then."
  [path reject message timeout-ms]
  (js/setTimeout (fn []
                   (when (get-in @!wire path)
                     (swap! !wire update-in (pop path) dissoc (peek path))
                     (reject (js/Error. message))))
                 timeout-ms))

(defn request
  "Sends `msg` and returns a promise of the parsed reply. Rejects after `timeout-ms`."
  ([msg] (request msg nil 5000))
  ([msg extra timeout-ms]
   (let [id (str (next-id!))]
     (js/Promise.
      (fn [resolve reject]
        (swap! !wire assoc-in [:pending id] resolve)
        (expire! [:pending id] reject (str "No reply to " (:fn-id msg) " within " timeout-ms " ms") timeout-ms)
        (send! msg id extra))))))

(defn next-message
  "Returns a promise of the next parsed message from the user content frame that satisfies `pred`.
   Rejects after `timeout-ms`."
  [pred timeout-ms]
  (let [id (str (next-id!))]
    (js/Promise.
     (fn [resolve reject]
       (swap! !wire assoc-in [:watchers id] [pred resolve])
       (expire! [:watchers id] reject (str "No matching message within " timeout-ms " ms") timeout-ms)))))

;; REPL cells

;; A timer in the var sheet asks the app for a formula every tick, and writes
;; the answer into the eval cell next to it. Formulas only have an evaluator
;; while they evaluate, so the form goes into the eval cell as a formula. The
;; next tick sends the eval cell's value, [id [:ok value]] or [id [:err message]].

(def ^:private repl-timer
  {:control {:type :timer
             :interval 100
             :repeat true
             :on-tick [{:type :api-server
                        :request [::repl 'R1]
                        ;; An idle reply reaches :on-response as {:error nil}, which :set :value rejects
                        :on-response [{:set ''R1 :value '(fn [formula] (when (string? formula) formula))}]}]}})

(defn add-repl-cells
  "Returns `project` with the REPL cells that `evaluate` needs, at X9Y10 and X10Y10 of the var sheet."
  [project]
  (-> project
      (set-input (coord 0 :f [[9 10]]) (formula repl-timer))
      (set-input (coord 0 :v [[10 10]]) nil)))

(defn- eval-formula [id form]
  (formula (list 'vector id (list 'try
                                  [:ok (list 'cellsify/eval (list 'read-string (pr-str form)))]
                                  '(catch :default e [:err (ex-message e)])))))

(defn evaluate
  "Returns a promise of the value of `form`, evaluated in the user content frame by the REPL cells.
   Rejects with the evaluation error, or after `timeout-ms`."
  ([form] (evaluate form 10000))
  ([form timeout-ms]
   (let [id (next-id!)]
     (js/Promise.
      (fn [resolve reject]
        (swap! !wire #(-> %
                          (assoc-in [:repl :pending id] [resolve reject])
                          (update-in [:repl :queue] (fnil conj []) [id (eval-formula id form)])))
        (expire! [:repl :pending id] reject (str "No REPL cells answered within " timeout-ms " ms") timeout-ms))))))

(defn- error-message [err]
  (or (second (re-find #":error-message \"(.*?)\"" err)) err))

(defn- settle-evaluation! [[id [outcome value]]]
  (when-let [[resolve reject] (get-in @!wire [:repl :pending id])]
    (swap! !wire update-in [:repl :pending] dissoc id)
    (if (= :ok outcome)
      (resolve value)
      (reject (js/Error. (error-message value))))))

(defn- next-eval-formula!
  "Returns the next queued formula whose evaluation is still pending, removing it and any expired ones before it."
  []
  (let [pending? (fn [[id]] (get-in @!wire [:repl :pending id]))
        [[_ next-formula] & queue] (drop-while (complement pending?) (get-in @!wire [:repl :queue]))]
    (swap! !wire assoc-in [:repl :queue] (vec queue))
    next-formula))

(defn- answer-repl-cells [[_ eval-cell-value]]
  (some-> eval-cell-value settle-evaluation!)
  (next-eval-formula!))

;; Messages from the user content frame

(defn- reply-local-server! [{:keys [message correlation-id]}]
  (let [handler (cond
                  (= ::repl (first (:request message))) answer-repl-cells
                  (:local-server-handler @!wire) (:local-server-handler @!wire)
                  :else (fn [_] (throw (js/Error. "No local server handler. Use pez.cellsify/serve!"))))]
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (handler (:request message))))
        (.then (fn [v] {:ok v})
               (fn [e] {:err (.-message e)}))
        (.then (fn [reply]
                 (send! (merge {:fn-id :local-server-request-response} reply) correlation-id nil))))))

(defn- notify-watchers! [parsed]
  (doseq [[id [pred resolve]] (:watchers @!wire)
          :when (pred parsed)]
    (swap! !wire update :watchers dissoc id)
    (resolve parsed)))

(defn- on-message [e]
  (when (identical? (.-source e) (user-content-window))
    (when-let [{:keys [message correlation-id] :as parsed} (parse-message (.-data e))]
      (notify-watchers! parsed)
      (if-let [resolve (get-in @!wire [:pending correlation-id])]
        (do (swap! !wire update :pending dissoc correlation-id)
            (resolve parsed))
        (when (= :local-server-request (:fn-id message))
          (reply-local-server! parsed))))))

(defn- listen! []
  (when-let [old (.-__pezCellsifyListener js/window)]
    (js/window.removeEventListener "message" old))
  (let [listener (fn [e] (on-message e))]
    (set! (.-__pezCellsifyListener js/window) listener)
    (js/window.addEventListener "message" listener)))

(listen!)

;; Operations

(defn pull
  "Returns a promise of the project open in Cellsify."
  []
  (-> (request {:fn-id :request-project-export} nil 10000)
      (.then (comp parse-export :extra))))

(defn push!
  "Imports `project` into Cellsify, replacing the open project. Returns a promise of the reply."
  ([project] (push! project 30000))
  ([project timeout-ms]
   (request {:fn-id :import-project} (export-text project) timeout-ms)))

(defn switch-sheet! [sheet-id]
  (send! {:fn-id :switch-to-sheet-by-key :sheet-id sheet-id :keys-pressed #{}}))

(defn serve!
  "Answers {:type :api-server :request ...} actions in cells with `(handler request)`.
   The handler may return a promise. Its value reaches the action's :on-response."
  [handler]
  (swap! !wire assoc :local-server-handler handler)
  :serving)

(defn look
  "Returns a promise of an image data URL of the user content frame."
  ([] (look {}))
  ([opts]
   (-> (tools/capture-element (user-content-frame) (merge {:format "jpeg" :quality 60} opts))
       (.then #(:dataUrl %)))))

(defn- sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- ^:async poll
  "Resolves with the first truthy result of `(f)`, which may be a promise, checking every `interval-ms`.
   Rejects with `message` after `deadline`."
  [f interval-ms message deadline]
  (let [v (await (f))]
    (cond
      v v
      (> (js/Date.now) deadline) (throw (js/Error. message))
      :else (do (await (sleep interval-ms))
                (await (poll f interval-ms message deadline))))))

(defn- open-project-name []
  (-> (pull)
      (.then #(-> % :project/meta :project :name))
      (.catch (constantly nil))))

(defn ^:async reload-user-content!
  "Reloads the user content frame, which recovers it after a crash and stops all timers.
   Resolves with the project name when the app has loaded its project there. Pushing earlier gets overwritten."
  []
  (let [frame (user-content-frame)
        started (next-message #(= :sheets-updated (-> % :message :fn-id)) 30000)]
    (set! (.-src frame) (.getAttribute frame "src"))
    (await started)
    ;; The user content frame starts with an empty "New Project" until the app sends its project
    (await (poll #(.then (open-project-name) (fn [n] (when (not= "New Project" n) n)))
                 500
                 "The app did not load a project into the user content frame"
                 (+ (js/Date.now) 30000)))))

;; The app's projects

(defn- button-labeled [label]
  (->> (js/document.querySelectorAll "button")
       (filter #(= label (str/trim (.-textContent %))))
       first))

(defn- type-into!
  "Types `text` into `input` so that the app sees the change."
  [input text]
  (.call (.-set (js/Object.getOwnPropertyDescriptor (.-prototype js/HTMLInputElement) "value")) input text)
  (.dispatchEvent input (js/Event. "input" #js {:bubbles true})))

(defn ^:async create-project!
  "Creates a project named `project-name` in the app, the way its New Project button does, and makes it the app's
   current project. Resolves when the user content frame has started it."
  [project-name]
  (.click (button-labeled "New Project"))
  (let [input (await (poll #(js/document.querySelector "input[placeholder='Project Name']")
                           100 "The app did not open its New Project dialog" (+ (js/Date.now) 5000)))]
    (type-into! input project-name)
    (.click (button-labeled "Create"))
    (await (poll #(.then (open-project-name) (fn [n] (= project-name n)))
                 200 (str "The user content frame did not start " project-name) (+ (js/Date.now) 10000)))))

(defn- save-button []
  (some-> (js/document.querySelector ".footer-button use[href*='floppy-disk']")
          (.closest ".footer-button")))

(defn- app-current-project-name
  "Returns the name of the app's current project, the card in its project list that lists sheets."
  []
  (some->> (js/document.querySelectorAll ".project-card")
           (filter #(str/includes? (.-textContent %) "+ sheet"))
           first
           (#(.querySelector % ".project-card-heading"))
           .-textContent
           str/trim))

(defn- close-save-notice!
  "Closes the notice about browser storage that the app shows after every save."
  []
  (when-let [close (button-labeled "Close")]
    (when (str/includes? (.. close -parentElement -textContent) "Be aware")
      (.click close))))

(defn ^:async save!
  "Saves the open project into the app's current project by clicking the app's save button. Both must be named
   `project-name`, or it throws without saving. Resolves when the app has stored it, so it survives a page reload."
  [project-name]
  (let [open-name (await (open-project-name))
        current-name (app-current-project-name)]
    (when-not (= project-name open-name current-name)
      (throw (js/Error. (str "Not saving " project-name ": the user content frame has " open-name
                             " open and the app's current project is " current-name))))
    (let [project-text (next-message #(= :save-project-string (-> % :message :fn-id)) 10000)]
      (.click (save-button))
      (await project-text)
      ;; The app colours the save button while it saves
      (await (poll #(= "" (.. (save-button) -style -color))
                   100 "The app did not finish saving" (+ (js/Date.now) 10000)))
      (close-save-notice!)
      project-name)))

(defn ^:async push-new!
  "Creates a project in the app named after `project`, pushes `project` into it, and saves it there."
  [project]
  (let [project-name (-> project :project/meta :project :name)]
    (await (reload-user-content!))
    (await (create-project! project-name))
    (await (push! project))
    (await (save! project-name))))

(comment
  (-> (pull) (.then #(-> % :project/meta :project)))

  (-> (new-project "hello")
      (add-sheet {:sheet-id 1 :sheet-name "Main" :rows 3 :columns 3})
      (set-input (coord 1 :v [[1 1]]) (formula '(+ 1 2 3)))
      (name-cell "six" (coord 1 :v [[1 1]]))
      add-repl-cells
      push-new!)

  (evaluate '(* six 7))

  (save! "hello")

  (look)

  (serve! (fn [request] {:echo request}))
  :rcf)
