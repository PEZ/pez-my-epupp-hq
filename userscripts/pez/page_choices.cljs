{:epupp/script-name "pez/page_choices.cljs"
 :epupp/auto-run-match "*"
 :epupp/description "See this page's Statsig experiments, and change the ones this visit is in."
 :epupp/run-at "document-start"
 :epupp/inject ["scittle://replicant.js"
                "epupp://epupp/ui.cljs"]}

(ns pez.page-choices
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [epupp.ui :as ui]
            [replicant.dom :as r]))

(def storage-key "pez.page-choices")
(def panel-id "pez-page-choices")

(def ink "rgb(13, 13, 13)")
(def paper "#ffffff")
(def quiet "#6e6e6e")
(def line "#ececec")
(def page-face "-apple-system-body, ui-sans-serif, -apple-system, system-ui, \"Segoe UI\", Helvetica, Arial, sans-serif")

(defonce !state (atom {:page/ready? false
                       :page/open? false
                       :page/experiments []
                       :page/changes {}
                       :page/sent nil
                       :page/started? false
                       :page/saw-statsig? false
                       :page/watching? false
                       :page/watch nil}))

(defn close-icon
  "Codicon close mark, the same one Epupp uses."
  [& {:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size :height size
         :viewBox "0 0 16 16"
         :fill "currentColor"}
   [:path {:d "M8.70701 8.00001L12.353 4.35401C12.548 4.15901 12.548 3.84201 12.353 3.64701C12.158 3.45201 11.841 3.45201 11.646 3.64701L8.00001 7.29301L4.35401 3.64701C4.15901 3.45201 3.84201 3.45201 3.64701 3.64701C3.45201 3.84201 3.45201 4.15901 3.64701 4.35401L7.29301 8.00001L3.64701 11.646C3.45201 11.841 3.45201 12.158 3.64701 12.353C3.74501 12.451 3.87301 12.499 4.00101 12.499C4.12901 12.499 4.25701 12.45 4.35501 12.353L8.00101 8.70701L11.647 12.353C11.745 12.451 11.873 12.499 12.001 12.499C12.129 12.499 12.257 12.45 12.355 12.353C12.55 12.158 12.55 11.841 12.355 11.646L8.70901 8.00001H8.70701Z"}]])

(defn humanize
  "Turns a raw setting key into a short label."
  [raw]
  (let [[head & tail] (string/split (str raw) #"_")]
    (str (string/capitalize (or head ""))
         (when (seq tail)
           (str " " (string/join " " tail))))))

(defn setting-kind
  "Classifies a site value as a control the panel can show."
  [value]
  (cond
    (boolean? value) :bool
    (number? value) :number
    (string? value) :text
    :else :locked))

(defn with-titles
  "Numbers later copies of a repeated experiment label."
  [experiments]
  (let [counts (frequencies (map :experiment/group experiments))]
    (first
     (reduce (fn [[out seen] {:experiment/keys [group] :as experiment}]
               (let [i (get seen group 1)]
                 [(conj out (assoc experiment :experiment/title
                                   (if (and (> (get counts group) 1)
                                            (> i 1))
                                     (str group " \u00b7 " i)
                                     group)))
                  (assoc seen group (inc i))]))
             [[] {}]
             experiments))))

(defn experiment-from-config
  "Builds one experiment map from a Statsig config entry."
  [key obj]
  (when (.-group_name obj)
    (let [value (or (.-value obj) #js {})
          names (js/Object.keys value)]
      {:experiment/id (str (or (.-name obj) key))
       :experiment/group (.-group_name obj)
       :experiment/in-effect? (boolean (and (.-is_experiment_active obj)
                                            (.-is_user_in_experiment obj)))
       :experiment/settings
       (mapv (fn [j]
               (let [param (aget names j)
                     site (aget value param)]
                 {:setting/param param
                  :setting/label (humanize param)
                  :setting/kind (setting-kind site)
                  :setting/site site}))
             (range (.-length names)))})))

(defn read-experiments
  "Reads every experiment the page sent."
  [statsig]
  (with-titles
   (vec
    (mapcat
     (fn [configs]
       (when configs
         (let [keys (js/Object.keys configs)]
           (keep (fn [i]
                   (let [key (aget keys i)]
                     (experiment-from-config key (aget configs key))))
                 (range (.-length keys))))))
     [(.-dynamic_configs statsig) (.-layer_configs statsig)]))))

(defn read-changes
  "Returns saved setting overrides."
  []
  (if-let [raw (.getItem js/localStorage storage-key)]
    (into {}
          (keep (fn [[id params]]
                  (let [settings (into {} (remove #(string/starts-with? (key %) "__") params))]
                    (when (seq settings) [id settings]))))
          (js->clj (js/JSON.parse raw)))
    {}))

(defn put-change
  "Sets or clears one override, dropping it when it matches the site."
  [{:override/keys [changes]
    :experiment/keys [id]
    :setting/keys [param site next]}]
  (let [params (if (= next site)
                 (dissoc (get changes id) param)
                 (assoc (get changes id) param next))]
    (if (seq params)
      (assoc changes id params)
      (dissoc changes id))))

(defn change-count
  "Counts saved setting overrides."
  [changes]
  (reduce + 0 (map count (vals changes))))

(defn shown-value
  "Returns the override when there is one, otherwise the site value."
  [{:override/keys [changes]
    :experiment/keys [id]
    :setting/keys [param site]}]
  (get-in changes [id param] site))

(defn changed?
  "True when this setting differs from what the page sent."
  [override]
  (not= (shown-value override) (:setting/site override)))

(defn write-config-params!
  "Writes one experiment's overrides onto its config."
  [config params]
  (when (seq params)
    (set! (.-is_user_in_experiment config) true)
    (let [value (or (.-value config) (js-obj))]
      (doseq [[param value*] params]
        (aset value param (clj->js value*)))
      (set! (.-value config) value))))

(defn apply-changes!
  "Writes saved setting values into experiment data."
  [statsig changes]
  (let [dynamic (.-dynamic_configs statsig)
        layer (.-layer_configs statsig)]
    (doseq [[id params] changes]
      (when-let [config (or (when dynamic (aget dynamic id))
                            (when layer (aget layer id)))]
        (write-config-params! config params))))
  statsig)

(defn statsig-client
  []
  (when-let [statsig js/window.__STATSIG__]
    (or (.-firstInstance statsig)
        (when-let [instances (.-instances statsig)]
          (let [keys (js/Object.keys instances)]
            (when (pos? (.-length keys))
              (aget instances (aget keys 0))))))))

(defn live-statsig
  "The experiment data the running page is reading."
  []
  (some-> (statsig-client) .-_store .-_values .-_values))

(defn original-parse
  []
  (or (.-__pezOriginalParse js/JSON) (.-parse js/JSON)))

(defn bootstrap-statsig
  "Reads Statsig payload from the page bootstrap script."
  []
  (when-let [el (js/document.getElementById "client-bootstrap")]
    (when-let [value (.call (original-parse) js/JSON (.-textContent el))]
      (.-statsigPayload value))))

(defn copy-config-bucket!
  "Copies experiment assignments for one config bucket."
  [source target]
  (when (and source target)
    (let [ids (js/Object.keys source)]
      (dotimes [i (.-length ids)]
        (let [id (aget ids i)
              src (aget source id)
              dst (aget target id)]
          (when (and src dst)
            (set! (.-is_user_in_experiment dst) (.-is_user_in_experiment src))
            (set! (.-group_name dst) (.-group_name src))
            (set! (.-value dst) (js/Object.assign #js {} (.-value src)))))))))

(defn copy-assignments!
  "Copies each experiment from one payload onto another."
  [from to]
  (copy-config-bucket! (.-dynamic_configs from) (.-dynamic_configs to))
  (copy-config-bucket! (.-layer_configs from) (.-layer_configs to))
  to)

(defn counted-label
  "Joins a count with its singular or plural word."
  [n one many]
  (str n " " (if (= 1 n) one many)))

(defn status-line
  "How many experiments and settings are on the page, and how many settings differ."
  [ready? experiments changes]
  (if ready?
    (str (counted-label (count experiments) "experiment" "experiments")
         ". "
         (counted-label (count (mapcat :experiment/settings experiments)) "setting" "settings")
         ". "
         (counted-label (change-count changes) "override" "overrides")
         ".")
    "Reading the experiments on this page."))

(defn field-style []
  {:font-family "inherit"
   :font-size "14px"
   :color ink
   :background paper
   :border (str "1px solid " line)
   :border-radius "8px"
   :padding "4px 8px"
   :width "8rem"})

(defn switch
  "An on/off control drawn by us, so the page cannot hide it."
  [on? label action]
  [:button {:type "button"
            :role "switch"
            :aria-checked (boolean on?)
            :aria-label label
            :on {:click [action]}
            :style {:width "36px" :height "22px" :padding "2px"
                    :border "none" :border-radius "999px" :cursor "pointer"
                    :flex-shrink "0" :display "flex" :align-items "center"
                    :justify-content (if on? "flex-end" "flex-start")
                    :background (if on? ink "#e3e3e3")}}
   [:span {:style {:width "18px" :height "18px" :border-radius "999px"
                   :background paper :display "block"}}]])

(defn setting-override
  "The override a setting row reads and writes."
  [changes {:experiment/keys [id]} {:setting/keys [param site]}]
  {:override/changes changes
   :experiment/id id
   :setting/param param
   :setting/site site})

(defn setting-control
  [override {:setting/keys [label kind]}]
  (let [current (shown-value override)]
    (case kind
      :bool (switch (boolean current)
                    label
                    [:setting/ax.commit (assoc override :setting/next (not (boolean current)))])
      :text [:input {:type "text" :value (str current) :aria-label label
                     :style (field-style)
                     :on {:change [[:setting/ax.commit (assoc override :setting/next :event/target.value)]]}}]
      :number [:input {:type "number" :value (str current) :aria-label label
                       :style (field-style)
                       :on {:change [[:setting/ax.commit-number (assoc override :setting/raw :event/target.value)]]}}]
      [:span {:style {:color quiet :font-size "13px"}} "Left as sent"])))

(defn setting-row
  [changes experiment {:setting/keys [label] :as setting}]
  (let [override (setting-override changes experiment setting)
        edited? (changed? override)]
    [:div {:style {:display "flex" :justify-content "space-between"
                   :align-items "center" :gap "12px"}}
     [:span label]
     [:span {:style {:display "flex" :align-items "center" :gap "8px" :flex-shrink "0"}}
      (when edited?
        [:button {:type "button"
                  :on {:click [[:setting/ax.commit (assoc override :setting/next (:setting/site override))]]}
                  :style {:font "inherit" :font-size "12px" :color quiet
                          :background "transparent" :border "none" :padding "0"
                          :cursor "pointer" :text-decoration "underline"
                          :text-underline-offset "2px"}}
         "Reset"])
      (setting-control override setting)]]))

(defn experiment-block
  [changes {:experiment/keys [title settings] :as experiment}]
  [:section {:style {:display "flex" :flex-direction "column" :gap "8px"}}
   [:h2 {:style {:margin "0" :font-family page-face :font-size "16px"
                 :font-weight "600" :line-height "1.3" :color ink}}
    title]
   (when (seq settings)
     [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
      (for [{:setting/keys [param] :as setting} settings]
        ^{:key param}
        (setting-row changes experiment setting))])])

(defn panel-header []
  [:div {:style {:display "flex" :align-items "flex-start"
                 :justify-content "space-between" :gap "8px"}}
   [:div {:style {:min-width "0" :flex "1"}}
    (ui/epupp-header :size 22 :title "Active A/B experiments" :tagline false)]
   [:button {:type "button" :aria-label "Close"
             :on {:click [[:panel/ax.close]]}
             :style {:width "28px" :height "28px" :padding "0" :border "none"
                     :border-radius "8px" :background "transparent" :color ink
                     :cursor "pointer" :display "flex" :align-items "center"
                     :justify-content "center" :flex-shrink "0"}}
    (close-icon :size 16)]])

(defn experiment-list [experiments changes]
  (when (seq experiments)
    [:div {:style {:display "flex" :flex-direction "column" :gap "16px" :margin-top "16px"}}
     (for [{:experiment/keys [id] :as experiment} experiments]
       ^{:key id}
       (experiment-block changes experiment))]))

(defn text-button [label action]
  [:button {:type "button"
            :on {:click [action]}
            :style {:font-family "inherit" :font-size "14px" :color ink
                    :background "transparent" :border "none" :padding "0"
                    :cursor "pointer" :text-decoration "underline"
                    :text-underline-offset "3px"}}
   label])

(defn panel-actions [changes]
  [:div {:style {:display "flex" :gap "16px" :margin-top "20px"}}
   (text-button "Reload" [:page/ax.reload])
   (when (pos? (change-count changes))
     (text-button "Put the page's experiments back" [:page/ax.restore]))])

(defn panel-top
  "Title and counts, kept in view while the list scrolls."
  [ready? experiments changes]
  [:div {:style {:flex-shrink "0"
                 :padding "12px 14px"
                 :background paper
                 :border-bottom (str "1px solid " line)}}
   (panel-header)
   [:p {:style {:margin "12px 0 0" :color quiet}}
    (status-line ready? experiments changes)]])

(defn launcher
  "Fixed button that opens the experiment panel."
  []
  [:button {:type "button"
            :aria-label "Active A/B experiments"
            :title "Active A/B experiments"
            :on {:click [[:panel/ax.show]]}
            :style {:position "fixed"
                    :bottom "16px"
                    :right "16px"
                    :width "44px"
                    :height "44px"
                    :z-index "2147483646"
                    :padding "0"
                    :display "flex"
                    :align-items "center"
                    :justify-content "center"
                    :background paper
                    :color ink
                    :border (str "1px solid " line)
                    :border-radius "14px"
                    :box-shadow "0 8px 28px rgba(0, 0, 0, 0.08)"
                    :cursor "pointer"}}
   (ui/epupp-icon :size 22)])

(defn panel
  [{:page/keys [ready? open? experiments changes]}]
  (when open?
    [:div {:style {:position "fixed" :top "12px" :right "12px"
                   :z-index "2147483646" :width "340px"
                   :max-height "calc(100vh - 24px)" :overflow "hidden"
                   :display "flex" :flex-direction "column"
                   :box-sizing "border-box" :padding "0"
                   :background paper :color ink :font-family page-face
                   :font-size "14px" :line-height "1.4"
                   :border (str "1px solid " line) :border-radius "16px"
                   :box-shadow "0 8px 28px rgba(0, 0, 0, 0.08)"}}
     (panel-top ready? experiments changes)
     (when ready?
       [:div {:style {:overflow "auto" :min-height "0" :flex "1"
                      :padding "0 14px 16px"}}
        (experiment-list (with-titles experiments) changes)
        (panel-actions changes)])]))

(defn shell
  "Shows the panel or the launcher button."
  [state]
  (if (:page/open? state)
    (panel state)
    (launcher)))

(defn enrich-from-event [{:replicant/keys [js-event]} action]
  (walk/postwalk
   (fn [x]
     (if (= :event/target.value x)
       (some-> js-event .-target .-value)
       x))
   action))

(defn enrich-action [replicant-data action]
  (enrich-from-event replicant-data action))

(defn with-render
  "Commits state and asks the panel to show it."
  [db extra-fxs]
  {:uf/db db
   :uf/fxs (into extra-fxs [[:ui/fx.render db]])})

(defn commit-setting
  "Saves one override and writes it into the page."
  [state override]
  (let [{:page/keys [changes]} state
        changes (put-change (assoc override :override/changes changes))
        db (assoc state :page/changes changes)]
    (with-render db [[:storage/fx.write changes]
                     [:page/fx.honor (:page/sent db) changes]])))

(defn commit-number
  "Turns a typed number into a setting override."
  [override]
  (let [parsed (js/parseFloat (:setting/raw override))]
    (when-not (js/Number.isNaN parsed)
      {:uf/dxs [[:setting/ax.commit (assoc override :setting/next parsed)]]})))

(defn handle-action
  "Decides the next state and which effects should run."
  [state _uf-data action]
  (let [[op & args] action]
    (case op
      :panel/ax.show
      (with-render (assoc state :page/open? true) [])

      :panel/ax.close
      (with-render (assoc state :page/open? false) [])

      :setting/ax.commit
      (commit-setting state (first args))

      :setting/ax.commit-number
      (commit-number (first args))

      :page/ax.restore
      (let [db (assoc state :page/changes {})]
        (with-render db [[:storage/fx.write {}]
                         [:page/fx.honor (:page/sent db) {}]]))

      :page/ax.reload
      {:uf/fxs [[:page/fx.reload]]}

      :page/ax.loaded
      (let [[{:page/keys [sent experiments changes]}] args]
        (with-render {:page/ready? true
                      :page/open? true
                      :page/started? true
                      :page/saw-statsig? true
                      :page/watching? false
                      :page/watch nil
                      :page/sent sent
                      :page/experiments (or experiments [])
                      :page/changes (or changes {})}
                     [[:page/fx.honor sent changes]]))

      :page/ax.kick
      (if (:page/started? state)
        (with-render (assoc state :page/open? true) [[:page/fx.install]])
        {:uf/db state
         :uf/fxs [[:page/fx.install] [:page/fx.probe]]
         :uf/dxs [[:page/ax.probed :uf/prev-result]]})

      :page/ax.saw-statsig
      (let [db (assoc state :page/saw-statsig? true)]
        (if (:page/started? state)
          {:uf/db db}
          {:uf/db db
           :uf/fxs [[:page/fx.probe]]
           :uf/dxs [[:page/ax.probed :uf/prev-result]]}))

      :page/ax.look
      (cond
        (and (:page/started? state) (:page/watch state))
        {:uf/db (assoc state :page/watch nil :page/watching? false)
         :uf/fxs [[:page/fx.clear-watch (:page/watch state)]]}

        (:page/started? state)
        {:uf/db state}

        :else
        {:uf/fxs [[:page/fx.probe]]
         :uf/dxs [[:page/ax.probed :uf/prev-result]]})

      :page/ax.probed
      (let [probe (first args)
            found? (or (:page/saw-statsig? state)
                       (:statsig/live? probe)
                       (:statsig/bootstrap? probe))
            db (if found? (assoc state :page/saw-statsig? true) state)]
        (cond
          (:page/started? state)
          (with-render (assoc db :page/open? true) [])

          (and found? (:dom/body? probe))
          (let [db* (assoc db :page/started? true :page/watching? false :page/watch nil)
                fxs (cond-> []
                      (:page/watch state) (conj [:page/fx.clear-watch (:page/watch state)])
                      true (conj [:page/fx.load (:page/sent state)]))]
            {:uf/db db*
             :uf/fxs fxs
             :uf/dxs [[:page/ax.loaded :uf/prev-result]]})

          (:page/watching? state)
          {:uf/db db}

          :else
          {:uf/db (assoc db :page/watching? true)
           :uf/fxs [[:page/fx.arm]]
           :uf/dxs [[:page/ax.armed :uf/prev-result]]}))

      :page/ax.armed
      (let [timer (first args)]
        (if (:page/started? state)
          {:uf/fxs [[:page/fx.clear-watch timer]]}
          {:uf/db (assoc state :page/watch timer)}))

      :page/ax.expire
      (let [timer (first args)]
        (if (= timer (:page/watch state))
          {:uf/db (assoc state :page/watch nil :page/watching? false)
           :uf/fxs [[:page/fx.clear-watch timer]]}
          {:uf/db state}))

      :uf/unhandled-ax)))

(defn honor!
  "Copies original assignments, then applies overrides."
  [sent changes]
  (when-let [live (live-statsig)]
    (when-let [original (or (bootstrap-statsig) sent)]
      (copy-assignments! original live))
    (when (seq changes)
      (apply-changes! live changes))))

(defn parse-json [original text reviver]
  (if (undefined? reviver)
    (.call original js/JSON text)
    (.call original js/JSON text reviver)))

(defn with-saved-overrides
  "Applies saved overrides to parsed bootstrap payload."
  [dispatch value]
  (when-let [statsig (when (some? value) (.-statsigPayload value))]
    (apply-changes! statsig (read-changes))
    (js/setTimeout #(dispatch [[:page/ax.saw-statsig]]) 0))
  value)

(defn install-rewrite!
  "Rewrites parsed JSON when it carries a Statsig payload."
  [dispatch]
  (let [original (or (.-__pezOriginalParse js/JSON)
                     (when-not (.-__pezPageChoices js/JSON)
                       (.-parse js/JSON)))]
    (when original
      (set! (.-__pezOriginalParse js/JSON) original)
      (set! (.-parse js/JSON)
            (fn [text reviver]
              (with-saved-overrides dispatch (parse-json original text reviver))))
      (set! (.-__pezPageChoices js/JSON) true))))

(defn render-ui!
  "Mounts the panel root and renders the shell."
  [_dispatch [db]]
  (let [root (or (js/document.getElementById panel-id)
                 (when js/document.body
                   (let [el (js/document.createElement "div")]
                     (set! (.-id el) panel-id)
                     (.appendChild js/document.body el)
                     el)))]
    (when root
      (r/render root (or (shell db) [:span])))))

(defn write-storage! [_dispatch [changes]]
  (if (empty? changes)
    (.removeItem js/localStorage storage-key)
    (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js changes)))))

(defn honor-effect! [_dispatch [sent changes]]
  (honor! sent changes))

(defn clone-values
  [values]
  (.call (original-parse) js/JSON (js/JSON.stringify values)))

(defn load-page! [_dispatch [sent]]
  (let [bootstrap (bootstrap-statsig)
        snapshot (if bootstrap
                   nil
                   (or sent (when-let [live (live-statsig)] (clone-values live))))
        source (or bootstrap snapshot)]
    {:page/sent snapshot
     :page/experiments (when source (read-experiments source))
     :page/changes (read-changes)}))

(defn install-effect! [dispatch _args]
  (install-rewrite! dispatch))

(defn probe! [_dispatch _args]
  {:dom/body? (boolean js/document.body)
   :statsig/live? (boolean (live-statsig))
   :statsig/bootstrap? (boolean (bootstrap-statsig))})

(defn clear-watch! [_dispatch [timer]]
  (when timer
    (js/clearInterval timer)))

(defn reload-page! [_dispatch _args]
  (js/setTimeout #(.reload js/location) 50))

(defn arm-watch!
  "Polls until Statsig appears or the watch expires."
  [dispatch _args]
  (let [timer (js/setInterval #(dispatch [[:page/ax.look]]) 200)]
    (js/setTimeout #(dispatch [[:page/ax.expire timer]]) 90000)
    timer))

(def effect-handlers
  {:ui/fx.render render-ui!
   :storage/fx.write write-storage!
   :page/fx.honor honor-effect!
   :page/fx.load load-page!
   :page/fx.reload reload-page!
   :page/fx.install install-effect!
   :page/fx.probe probe!
   :page/fx.clear-watch clear-watch!
   :page/fx.arm arm-watch!})

(defn perform-effect! [dispatch [effect & args]]
  (if-let [handler (get effect-handlers effect)]
    (handler dispatch args)
    :uf/unhandled-fx))

(defn execute-effect! [dispatch fx]
  (let [result (perform-effect! dispatch fx)]
    (if (= :uf/unhandled-fx result)
      (js/console.warn "Unhandled effect:" fx)
      result)))

(defn replace-prev-result [form prev-result]
  (walk/postwalk (fn [x] (if (= :uf/prev-result x) prev-result x)) form))

(defn execute-effects! [dispatch fxs]
  (reduce (fn [prev fx]
            (execute-effect! dispatch (replace-prev-result fx prev)))
          nil
          (remove nil? fxs)))

(defn handle-actions [state uf-data actions]
  (reduce
   (fn [{:uf/keys [db] :as acc} action]
     (let [result (handle-action db uf-data action)]
       (when (= :uf/unhandled-ax result)
         (js/console.warn "Unhandled action:" action))
       (let [{:uf/keys [db fxs dxs]} (when (map? result) result)]
         (cond-> acc
           db (assoc :uf/db db)
           (seq fxs) (update :uf/fxs into fxs)
           (seq dxs) (update :uf/dxs into dxs)))))
   {:uf/db state :uf/fxs [] :uf/dxs []}
   (remove nil? actions)))

(defn dispatch!
  ([actions] (dispatch! actions nil))
  ([actions replicant-data]
   (let [uf-data {:uf/replicant-data replicant-data}
         enriched (mapv #(enrich-action replicant-data %) actions)
         {:uf/keys [db fxs dxs]} (handle-actions @!state uf-data enriched)]
     (when (some? db)
       (reset! !state db))
     (let [prev (when (seq fxs)
                  (execute-effects! dispatch! fxs))]
       (when (seq dxs)
         (dispatch! (mapv #(replace-prev-result % prev) dxs)))))))

(defn event-handler [replicant-data actions]
  (dispatch! actions replicant-data))

(defn kick!
  "Installs the parse hook at once, and opens the panel if Statsig shows up."
  []
  (r/set-dispatch! event-handler)
  (dispatch! [[:page/ax.kick]]))

(kick!)
