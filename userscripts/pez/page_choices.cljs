{:epupp/script-name "pez/page_choices.cljs"
 :epupp/auto-run-match ["https://chatgpt.com/*" "https://chat.openai.com/*"]
 :epupp/description "See the A/B experiments on this page, and change the ones this visit is in."
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
(def toolbar-id "pez-page-choices-toggle")

(def ink "rgb(13, 13, 13)")
(def paper "#ffffff")
(def quiet "#6e6e6e")
(def line "#ececec")
(def page-face "-apple-system-body, ui-sans-serif, -apple-system, system-ui, \"Segoe UI\", Helvetica, Arial, sans-serif")

(defonce !parse (atom (.-parse js/JSON)))
(defonce !state (atom {:page/ready? false
                       :page/open? false
                       :page/experiments []
                       :page/changes {}}))

(defn close-icon
  "Codicon close mark, the same one Epupp uses."
  [& {:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size :height size
         :viewBox "0 0 16 16"
         :fill "currentColor"}
   [:path {:d "M8.70701 8.00001L12.353 4.35401C12.548 4.15901 12.548 3.84201 12.353 3.64701C12.158 3.45201 11.841 3.45201 11.646 3.64701L8.00001 7.29301L4.35401 3.64701C4.15901 3.45201 3.84201 3.45201 3.64701 3.64701C3.45201 3.84201 3.45201 4.15901 3.64701 4.35401L7.29301 8.00001L3.64701 11.646C3.45201 11.841 3.45201 12.158 3.64701 12.353C3.74501 12.451 3.87301 12.499 4.00101 12.499C4.12901 12.499 4.25701 12.45 4.35501 12.353L8.00101 8.70701L11.647 12.353C11.745 12.451 11.873 12.499 12.001 12.499C12.129 12.499 12.257 12.45 12.355 12.353C12.55 12.158 12.55 11.841 12.355 11.646L8.70901 8.00001H8.70701Z"}]])

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

(defn read-experiments
  "Reads every experiment the page sent."
  [statsig]
  (let [configs (.-dynamic_configs statsig)
        keys (js/Object.keys configs)]
    (with-titles
     (vec
      (keep (fn [i]
              (let [obj (aget configs (aget keys i))]
                (when (.-group_name obj)
                  (let [value (or (.-value obj) #js {})
                        names (js/Object.keys value)]
                    {:experiment/id (str (.-name obj))
                     :experiment/group (.-group_name obj)
                     :experiment/in-effect? (boolean (and (.-is_experiment_active obj)
                                                          (.-is_user_in_experiment obj)))
                     :experiment/settings
                     (mapv (fn [j]
                             (let [param (aget names j)
                                   site (aget value param)]
                               {:setting/param param
                                :setting/label param
                                :setting/kind (setting-kind site)
                                :setting/site site}))
                           (range (.-length names)))}))))
            (range (.-length keys)))))))

(defn read-changes
  "Returns saved setting overrides."
  []
  (if-let [raw (.getItem js/localStorage storage-key)]
    (into {}
          (keep (fn [[id params]]
                  (let [settings (into {} (remove #(string/starts-with? (key %) "__") params))]
                    (when (seq settings) [id settings]))))
          (js->clj (.call @!parse js/JSON raw)))
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

(comment "duplicate change-count")

(comment "duplicate shown-value")

(comment "duplicate changed?")

(defn apply-changes!
  "Writes saved setting values into experiment data."
  [statsig changes]
  (let [configs (.-dynamic_configs statsig)]
    (doseq [[id params] changes]
      (when-let [config (aget configs id)]
        (when (seq params)
          (set! (.-is_user_in_experiment config) true)
          (let [value (or (.-value config) (js-obj))]
            (doseq [[param value*] params]
              (aset value param (clj->js value*)))
            (set! (.-value config) value))))))
  statsig)

(defn live-statsig
  "The experiment data the running page is reading."
  []
  (when-let [statsig js/window.__STATSIG__]
    (let [instances (.-instances statsig)
          client-key (when instances (aget (js/Object.keys instances) 0))
          client (when client-key (aget instances client-key))]
      (when client
        (.-_values (.-_values (.-_store client)))))))

(defn script-statsig
  "The experiment data as the page sent it."
  []
  (when-let [el (js/document.getElementById "client-bootstrap")]
    (.-statsigPayload (.call @!parse js/JSON (.-textContent el)))))

(defn copy-assignments!
  "Copies each experiment from one payload onto another."
  [from to]
  (let [source (.-dynamic_configs from)
        target (.-dynamic_configs to)
        ids (js/Object.keys source)]
    (dotimes [i (.-length ids)]
      (let [id (aget ids i)
            src (aget source id)
            dst (aget target id)]
        (when (and src dst)
          (set! (.-is_user_in_experiment dst) (.-is_user_in_experiment src))
          (set! (.-group_name dst) (.-group_name src))
          (set! (.-value dst) (js/Object.assign #js {} (.-value src))))))))

(defn status-line
  "How many settings differ from what the page sent."
  [ready? experiments changes]
  (let [n (change-count changes)]
    (cond
      (not ready?) "Reading the experiments on this page."
      (zero? (count experiments)) "No experiments arrived with this page."
      (= 1 n) "1 override."
      :else (str n " overrides."))))

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

(defn text-button [label action]
  [:button {:type "button"
            :on {:click [action]}
            :style {:font-family "inherit" :font-size "14px" :color ink
                    :background "transparent" :border "none" :padding "0"
                    :cursor "pointer" :text-decoration "underline"
                    :text-underline-offset "3px"}}
   label])

(defn panel
  [{:page/keys [ready? open? experiments changes]}]
  (when open?
    (let [experiments (with-titles experiments)]
      [:div {:style {:position "fixed" :top "12px" :right "12px"
                     :z-index "2147483646" :width "340px"
                     :max-height "calc(100vh - 24px)" :overflow "auto"
                     :box-sizing "border-box" :padding "12px 14px 16px"
                     :background paper :color ink :font-family page-face
                     :font-size "14px" :line-height "1.4"
                     :border (str "1px solid " line) :border-radius "16px"
                     :box-shadow "0 8px 28px rgba(0, 0, 0, 0.08)"}}
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
         (close-icon :size 16)]]
       [:p {:style {:margin "12px 0 0" :color quiet}}
        (status-line ready? experiments changes)]
       (when (and ready? (seq experiments))
         [:div {:style {:display "flex" :flex-direction "column" :gap "16px" :margin-top "16px"}}
          (for [{:experiment/keys [id] :as experiment} experiments]
            ^{:key id}
            (experiment-block changes experiment))])
       (when ready?
         [:div {:style {:display "flex" :gap "16px" :margin-top "20px"}}
          (text-button "Reload" [:page/ax.reload])
          (when (pos? (change-count changes))
            (text-button "Put the page's experiments back" [:page/ax.restore]))])])))

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
  "Commits state and asks the panel and toolbar to show it."
  [db extra-fxs]
  {:uf/db db
   :uf/fxs (into extra-fxs [[:ui/fx.render db]
                            [:toolbar/fx.sync (:page/open? db)]])})

(defn commit-setting
  "Saves one override and writes it into the page."
  [state override]
  (let [{:page/keys [changes]} state
        changes (put-change (assoc override :override/changes changes))
        db (assoc state :page/changes changes)]
    (with-render db [[:storage/fx.write changes]
                     [:page/fx.honor changes]])))

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
      :panel/ax.toggle
      (with-render (update state :page/open? not) [])

      :panel/ax.close
      (with-render (assoc state :page/open? false) [])

      :setting/ax.commit
      (commit-setting state (first args))

      :setting/ax.commit-number
      (commit-number (first args))

      :page/ax.restore
      (let [db (assoc state :page/changes {})]
        (with-render db [[:storage/fx.write {}]
                         [:page/fx.honor {}]]))

      :page/ax.reload
      {:uf/fxs [[:page/fx.reload]]}

      :page/ax.boot
      {:uf/fxs [[:page/fx.load]]
       :uf/dxs [[:page/ax.loaded :uf/prev-result]]}

      :page/ax.loaded
      (let [[{:page/keys [experiments changes]}] args
            db {:page/ready? true
                :page/open? (boolean (:page/open? state))
                :page/experiments (or experiments [])
                :page/changes (or changes {})}]
        (with-render db [[:page/fx.honor changes]]))

      :toolbar/ax.sync
      {:uf/fxs [[:toolbar/fx.sync (:page/open? state)]]}

      :uf/unhandled-ax)))

(comment "Earlier action handler, replaced by with-render.")

(declare dispatch!)

(defn toolbar-host
  "The column of icons in ChatGPT's left toolbar."
  []
  (when-let [nav (js/document.querySelector "nav")]
    (let [buttons (.querySelectorAll nav "button")
          parents (keep #(.-parentElement (aget buttons %))
                         (range (.-length buttons)))
          counts (frequencies parents)]
      (when (seq counts)
        (first (apply max-key val counts))))))

(defn paint-toolbar! [open?]
  (when-let [button (js/document.getElementById toolbar-id)]
    (set! (.. button -style -background)
          (if open? "rgba(13, 13, 13, 0.06)" "transparent"))))

(defn ensure-toolbar! [open?]
  (if-let [button (js/document.getElementById toolbar-id)]
    (do
      (when-let [host (toolbar-host)]
        (when-not (identical? (.-parentElement button) host)
          (.appendChild host button)))
      (paint-toolbar! open?))
    (when-let [host (toolbar-host)]
      (let [button (js/document.createElement "button")]
        (set! (.-id button) toolbar-id)
        (set! (.-type button) "button")
        (set! (.-title button) "Active A/B experiments")
        (set! (.-ariaLabel button) "Active A/B experiments")
        (set! (.. button -style -cssText)
              "width:36px;height:36px;border:none;border-radius:10px;padding:0;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;color:rgb(13,13,13);")
        (.addEventListener button "click"
                           (fn [event]
                             (.stopPropagation event)
                             (dispatch! [[:panel/ax.toggle]])))
        (.appendChild host button)
        (r/render button (ui/epupp-icon :size 22))
        (paint-toolbar! open?)))))

(defn honor! [changes]
  (when-let [live (live-statsig)]
    (when-let [original (script-statsig)]
      (copy-assignments! original live))
    (when (seq changes)
      (apply-changes! live changes))))

(defn perform-effect! [_dispatch [effect & args]]
  (case effect
    :ui/fx.render
    (let [[db] args
          root (or (js/document.getElementById panel-id)
                   (when js/document.body
                     (let [el (js/document.createElement "div")]
                       (set! (.-id el) panel-id)
                       (.appendChild js/document.body el)
                       el)))]
      (when root
        (r/render root (or (panel db) [:span]))))

    :toolbar/fx.sync
    (let [[open?] args]
      (ensure-toolbar! open?))

    :storage/fx.write
    (let [[changes] args]
      (if (empty? changes)
        (.removeItem js/localStorage storage-key)
        (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js changes)))))

    :page/fx.honor
    (let [[changes] args]
      (honor! changes))

    :page/fx.load
    {:page/experiments (when-let [statsig (script-statsig)]
                         (read-experiments statsig))
     :page/changes (read-changes)}

    :page/fx.reload
    (js/setTimeout #(.reload js/location) 50)

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

(defn install-rewrite!
  "Rewrites page data on parse, before ChatGPT reads it."
  []
  (when-not (.-__pezPageChoices js/JSON)
    (let [original (.-parse js/JSON)]
      (reset! !parse original)
      (set! (.-parse js/JSON)
            (fn [text reviver]
              (let [value (if (undefined? reviver)
                            (.call original js/JSON text)
                            (.call original js/JSON text reviver))]
                (when-let [statsig (when (some? value) (.-statsigPayload value))]
                  (apply-changes! statsig (read-changes)))
                value)))
      (set! (.-__pezPageChoices js/JSON) true))))

(defn watch-toolbar!
  "Puts the toolbar icon back whenever ChatGPT redraws the sidebar."
  []
  (when (and js/document.body
             (not (.-__pezPageChoicesBodyWatch js/document.body)))
    (set! (.-__pezPageChoicesBodyWatch js/document.body) true)
    (let [observer (js/MutationObserver.
                    (fn [_ _]
                      (when-not (js/document.getElementById toolbar-id)
                        (dispatch! [[:toolbar/ax.sync]]))))]
      (.observe observer js/document.body #js {:childList true :subtree true}))))

(defn boot! []
  (r/set-dispatch! event-handler)
  (dispatch! [[:page/ax.boot]])
  (watch-toolbar!))

(install-rewrite!)

(if js/document.body
  (boot!)
  (js/document.addEventListener "DOMContentLoaded" boot!))
