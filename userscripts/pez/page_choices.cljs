{:epupp/script-name "pez/page_choices.cljs"
 :epupp/auto-run-match "*"
 :epupp/description "See the Statsig switches on this page, and override them for this visit."
 :epupp/run-at "document-start"
 :epupp/inject ["scittle://replicant.js"
                "epupp://epupp/ui.cljs"]}

(ns pez.page-choices
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [epupp.ui :as ui]
            [replicant.dom :as r]))

(def storage-key "pez.page-choices")
(def panel-storage-key "pez.page-choices.panel")

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
                       :page/watch nil
                       :page/query ""
                       :page/open-ids #{}
                       :page/pins []}))

(defn close-icon
  "Codicon close mark, the same one Epupp uses."
  [& {:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size :height size
         :viewBox "0 0 16 16"
         :fill "currentColor"}
   [:path {:d "M8.70701 8.00001L12.353 4.35401C12.548 4.15901 12.548 3.84201 12.353 3.64701C12.158 3.45201 11.841 3.45201 11.646 3.64701L8.00001 7.29301L4.35401 3.64701C4.15901 3.45201 3.84201 3.45201 3.64701 3.64701C3.45201 3.84201 3.45201 4.15901 3.64701 4.35401L7.29301 8.00001L3.64701 11.646C3.45201 11.841 3.45201 12.158 3.64701 12.353C3.74501 12.451 3.87301 12.499 4.00101 12.499C4.12901 12.499 4.25701 12.45 4.35501 12.353L8.00101 8.70701L11.647 12.353C11.745 12.451 11.873 12.499 12.001 12.499C12.129 12.499 12.257 12.45 12.355 12.353C12.55 12.158 12.55 11.841 12.355 11.646L8.70901 8.00001H8.70701Z"}]])

(defn pin-icon
  "Codicon pin mark."
  [& {:keys [size] :or {size 16}}]
  [:svg {:width size
         :height size
         :viewBox "0 0 16 16"
         :xmlns "http://www.w3.org/2000/svg"
         :fill "currentColor"}
   [:path
    {:d
     "M10.0589 2.44511C9.34701 1.73063 8.14697 1.90829 7.67261 2.79839L5.6526 6.58878L2.8419 7.52568C2.6775 7.58048 2.5532 7.71649 2.51339 7.88514C2.47357 8.0538 2.52392 8.23104 2.64646 8.35357L4.79291 10.5L2.14645 13.1465L2 14L2.85356 13.8536L5.50002 11.2071L7.64646 13.3536C7.76899 13.4761 7.94623 13.5265 8.11489 13.4866C8.28354 13.4468 8.41955 13.3225 8.47435 13.1581L9.41143 10.3469L13.1897 8.32423C14.0759 7.84982 14.2538 6.6551 13.5443 5.94305L10.0589 2.44511ZM8.55511 3.2687C8.71323 2.972 9.11324 2.91278 9.35055 3.15094L12.836 6.64889C13.0725 6.88624 13.0131 7.28448 12.7178 7.44262L8.76403 9.55921C8.65137 9.61952 8.56608 9.72068 8.52567 9.84191L7.7815 12.0744L3.92562 8.21853L6.15812 7.47436C6.27966 7.43385 6.38101 7.34823 6.44126 7.23518L8.55511 3.2687Z"}]])

(defn pinned-icon
  "Codicon pinned mark."
  [& {:keys [size] :or {size 16}}]
  [:svg {:width size
         :height size
         :viewBox "0 0 16 16"
         :xmlns "http://www.w3.org/2000/svg"
         :fill "currentColor"}
   [:path
    {:d
     "M9.56016 10.2673L14.1464 14.8536C14.3417 15.0488 14.6583 15.0488 14.8536 14.8536C15.0488 14.6583 15.0488 14.3417 14.8536 14.1464L1.85355 1.14645C1.65829 0.951184 1.34171 0.951184 1.14645 1.14645C0.951184 1.34171 0.951184 1.65829 1.14645 1.85355L5.73223 6.43934L5.6526 6.58876L2.8419 7.52566C2.6775 7.58046 2.5532 7.71648 2.51339 7.88513C2.47357 8.05378 2.52392 8.23102 2.64646 8.35356L4.79291 10.5L2.14645 13.1465L2 14L2.85356 13.8536L5.50002 11.2071L7.64646 13.3536C7.76899 13.4761 7.94623 13.5264 8.11489 13.4866C8.28354 13.4468 8.41955 13.3225 8.47435 13.1581L9.41143 10.3469L9.56016 10.2673ZM8.82138 9.52849L8.76403 9.5592C8.65137 9.61951 8.56608 9.72066 8.52567 9.84189L7.7815 12.0744L3.92562 8.21851L6.15812 7.47435C6.27966 7.43383 6.38101 7.34822 6.44126 7.23516L6.47143 7.17854L8.82138 9.52849ZM12.7178 7.4426L10.6636 8.54227L11.4024 9.28105L13.1897 8.32422C14.0759 7.84981 14.2538 6.65509 13.5443 5.94304L10.0589 2.44509C9.34701 1.73062 8.14697 1.90828 7.67261 2.79838L6.71556 4.59421L7.45476 5.33341L8.55511 3.26869C8.71323 2.97199 9.11324 2.91277 9.35055 3.15093L12.836 6.64888C13.0725 6.88623 13.0131 7.28446 12.7178 7.4426Z"}]])

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

(def value-buckets ["feature_gates" "dynamic_configs" "layer_configs"])

(def config-buckets ["dynamic_configs" "layer_configs"])

(defn object-entries
  "Pairs of key and value from a JS object."
  [obj]
  (when obj
    (js/Object.entries obj)))

(defn read-gate
  "Builds one gate map from a Statsig feature gate."
  [key obj]
  (let [id (str (or (.-name obj) key))]
    {:experiment/id id
     :experiment/kind :gate
     :experiment/group id
     :experiment/title id
     :experiment/settings
     [{:setting/param "value"
       :setting/label "On"
       :setting/kind :bool
       :setting/site (boolean (.-value obj))}]}))

(defn read-config
  "Builds one config or layer map from a Statsig entry."
  [kind key obj]
  (let [id (str (or (.-name obj) key))]
    {:experiment/id id
     :experiment/kind kind
     :experiment/group (or (.-group_name obj) id)
     :experiment/settings
     (mapv (fn [[param site]]
             {:setting/param (str param)
              :setting/label (humanize param)
              :setting/kind (setting-kind site)
              :setting/site site})
           (object-entries (or (.-value obj) #js {})))}))

(defn read-named
  "Reads one config bucket into entry maps."
  [kind statsig bucket]
  (mapv (fn [[k obj]] (read-config kind k obj))
        (object-entries (aget statsig bucket))))

(defn read-experiments
  "Reads gates, configs, and layers that have settings."
  [statsig]
  (when statsig
    (filterv #(seq (:experiment/settings %))
             (concat
              (sort-by :experiment/id
                       (mapv (fn [[k obj]] (read-gate k obj))
                             (object-entries (.-feature_gates statsig))))
              (with-titles
                (vec
                 (sort-by (juxt :experiment/group :experiment/id)
                          (concat (read-named :config statsig "dynamic_configs")
                                  (read-named :layer statsig "layer_configs")))))))))

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
  "Writes saved overrides into gates, configs, and layers."
  [statsig changes]
  (doseq [[id params] changes
          bucket value-buckets
          :let [entry (some-> (aget statsig bucket) (aget id))]
          :when entry]
    (if (= bucket "feature_gates")
      (when (contains? params "value")
        (set! (.-value entry) (boolean (get params "value"))))
      (write-config-params! entry params)))
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
    (doseq [[id src] (object-entries source)]
      (when-let [dst (aget target id)]
        (set! (.-is_user_in_experiment dst) (.-is_user_in_experiment src))
        (set! (.-group_name dst) (.-group_name src))
        (set! (.-value dst) (js/Object.assign #js {} (.-value src)))))))

(defn copy-gates!
  "Copies gate values from one payload onto another."
  [source target]
  (when (and source target)
    (doseq [[id src] (object-entries source)]
      (when-let [dst (aget target id)]
        (set! (.-value dst) (.-value src))))))

(defn copy-assignments!
  "Copies each gate, config, and layer from one payload onto another."
  [from to]
  (copy-gates! (.-feature_gates from) (.-feature_gates to))
  (doseq [bucket config-buckets]
    (copy-config-bucket! (aget from bucket) (aget to bucket)))
  to)

(defn counted-ratio
  "Joins a shown count, its total, and the word for the total."
  [n total one many]
  (str n "/" total " " (if (= 1 total) one many)))

(defn counted-label
  "Joins a count with its singular or plural word."
  [n one many]
  (str n " " (if (= 1 n) one many)))

(defn filter-text
  "Lower-case trimmed filter text."
  [query]
  (string/lower-case (string/trim (or query ""))))

(defn experiment-matches?
  "True when the filter is empty or hits this experiment."
  [q {:experiment/keys [id group settings]}]
  (or (= "" q)
      (string/includes? (string/lower-case (str group)) q)
      (string/includes? (string/lower-case (str id)) q)
      (some (fn [{:setting/keys [param label]}]
              (or (string/includes? (string/lower-case (str param)) q)
                  (string/includes? (string/lower-case (str label)) q)))
            settings)))

(defn status-line
  "How many experiments and configs are listed, and how many overrides."
  [ready? experiments changes query]
  (if ready?
    (let [q (filter-text query)
          shown (if (= "" q)
                   experiments
                   (filter #(experiment-matches? q %) experiments))
          configs (fn [items]
                    (count (mapcat :experiment/settings items)))
          overrides (fn [items]
                      (reduce + 0 (map #(count (get changes (:experiment/id %))) items)))
          part (fn [n total one many]
                 (if (= "" q)
                   (counted-label n one many)
                   (counted-ratio n total one many)))]
      (str (part (count shown) (count experiments) "experiment" "experiments")
           ". "
           (part (configs shown) (configs experiments) "config" "configs")
           ". "
           (part (overrides shown) (overrides experiments) "override" "overrides")
           "."))
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

(defn locked-text
  "JSON text for a value the panel does not edit."
  [value]
  (if (nil? value)
    ""
    (js/JSON.stringify value)))

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
      (let [text (locked-text current)]
        [:input {:type "text"
                 :disabled true
                 :value text
                 :title text
                 :aria-label label
                 :style (assoc (field-style)
                               :color quiet
                               :background "#f6f6f6"
                               :cursor "default")}]))))

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

(def generic-header-stems
  #{"control" "test" "treatment" "visible" "empty"})

(defn ordered-sections
  "Sections with the most readable headers first. A bare number sorts last."
  [by-kind labels]
  (let [header (fn [experiment]
                 (str (or (:experiment/title experiment)
                          (:experiment/group experiment)
                          (:experiment/id experiment))))
        stem (fn [title]
               (-> title
                   string/trim
                   string/lower-case
                   (string/replace #"\s*\u00b7\s*\d+$" "")))
        rank (fn [experiment]
               (let [s (stem (header experiment))]
                 (cond
                   (re-matches #"\d+" s) 2
                   (contains? generic-header-stems s) 1
                   :else 0)))
        id-number (fn [experiment]
                    (let [n (js/parseInt (str (:experiment/id experiment)) 10)]
                      (if (js/Number.isNaN n) 0 n)))
        order (fn [experiment]
                (let [r (rank experiment)]
                  [r (if (= r 2)
                       (id-number experiment)
                       (string/lower-case (header experiment)))]))
        readable (fn [entries]
                   (count (filter #(zero? (rank %)) entries)))]
    (->> labels
         (map (fn [[title kind]]
                [title (sort-by order (get by-kind kind))]))
         (sort-by (fn [[_ entries]] (- (readable entries)))))))

(defn panel-header []
  [:div {:style {:display "flex" :align-items "flex-start"
                 :justify-content "space-between" :gap "8px"}}
   [:div {:style {:min-width "0" :flex "1"}}
    (ui/epupp-header :size 22 :title "Switches on this page" :tagline false)]
   [:button {:type "button" :aria-label "Close"
             :on {:click [[:panel/ax.close]]}
             :style {:width "28px" :height "28px" :padding "0" :border "none"
                     :border-radius "8px" :background "transparent" :color ink
                     :cursor "pointer" :display "flex" :align-items "center"
                     :justify-content "center" :flex-shrink "0"}}
    (close-icon :size 16)]])

(defn entry-block
  "A switch header. Its settings show only after it is opened."
  [open-ids pins changes {:experiment/keys [id title settings] :as experiment}]
  (let [open? (contains? open-ids id)
        pinned? (some #(= % id) pins)
        n (count (get changes id))]
    [:section {:style {:display "flex" :flex-direction "column" :gap "8px"}}
     [:div {:style {:display "flex" :align-items "flex-start" :gap "8px"}}
      [:button {:type "button"
                :aria-pressed (boolean pinned?)
                :aria-label (if pinned? "Unpin" "Pin")
                :title (if pinned? "Unpin" "Pin")
                :on {:click [[:page/ax.pin id]]}
                :style {:flex-shrink "0" :width "22px" :height "22px" :margin "0" :padding "0"
                        :border "none" :background "transparent"
                        :color (if pinned? ink quiet)
                        :cursor "pointer" :display "flex" :align-items "center"
                        :justify-content "center"}}
       (if pinned? (pinned-icon :size 16) (pin-icon :size 16))]
      [:button {:type "button"
                :aria-expanded open?
                :on {:click [[:page/ax.toggle id]]}
                :style {:margin "0" :padding "0" :border "none" :background "transparent"
                        :font-family page-face :font-size "16px" :font-weight "600"
                        :line-height "1.3" :color ink :text-align "left" :cursor "pointer"}}
       (str (if open? "\u25be " "\u25b8 ") title)
       (when (pos? n)
         (str " (" n ")"))]]
     (when (and open? (seq settings))
       [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
        (for [{:setting/keys [param] :as setting} settings]
          ^{:key param}
          (setting-row changes experiment setting))])]))

(defn experiment-list
  "Pinned gates first, then the other switches that match the filter."
  [{:page/keys [query open-ids pins]} experiments changes]
  (let [q (filter-text query)
        shown (filter #(experiment-matches? q %) experiments)
        pin-set (set pins)
        pinned (keep (fn [id]
                       (some #(when (= id (:experiment/id %)) %)
                             shown))
                     pins)
        by-kind (group-by :experiment/kind
                          (remove #(contains? pin-set (:experiment/id %)) shown))
        section (fn [title entries row]
                  (when (seq entries)
                    [:section {:style {:display "flex" :flex-direction "column" :gap "12px"}}
                     [:h2 {:style {:margin "0" :font-family page-face :font-size "13px"
                                   :font-weight "600" :letter-spacing "0.04em"
                                   :text-transform "uppercase" :color quiet}}
                      (str title " (" (count entries) ")")]
                     (for [experiment entries]
                       ^{:key (:experiment/id experiment)}
                       (row experiment))]))
        row #(entry-block open-ids pins changes %)]
    [:div {:style {:display "flex" :flex-direction "column" :gap "20px" :margin-top "16px"}}
     (section "Pinned" pinned row)
     (for [[title entries] (ordered-sections by-kind [["Configs" :config]
                                                      ["Layers" :layer]
                                                      ["Gates" :gate]])]
       (section title entries row))]))

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
     (text-button "Put the page's switches back" [:page/ax.restore]))])

(defn query-field
  "Filters the switch list. Empty text shows the full set."
  [query]
  [:input {:type "search"
           :value (or query "")
           :placeholder "Filter"
           :aria-label "Filter switches"
           :on {:input [[:page/ax.query :event/target.value]]}
           :style (assoc (field-style) :width "100%" :box-sizing "border-box" :margin-top "12px")}])

(defn panel-top
  "Title, counts, and filter, kept in view while the list scrolls."
  [ready? experiments changes query]
  [:div {:style {:flex-shrink "0"
                 :padding "12px 14px"
                 :background paper
                 :border-bottom (str "1px solid " line)}}
   (panel-header)
   [:p {:style {:margin "12px 0 0" :color quiet}}
    (status-line ready? experiments changes query)]
   (when ready?
     (query-field query))])

(defn launcher
  "Fixed button that opens the switch panel."
  []
  [:button {:type "button"
            :aria-label "Switches on this page"
            :title "Switches on this page"
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
  [{:page/keys [ready? experiments changes query] :as state}]
  [:div {:style {:position "fixed" :top "12px" :right "12px"
                 :z-index "2147483646" :width "340px"
                 :max-height "calc(100vh - 24px)" :overflow "hidden"
                 :display "flex" :flex-direction "column"
                 :box-sizing "border-box" :padding "0"
                 :background paper :color ink :font-family page-face
                 :font-size "14px" :line-height "1.4"
                 :border (str "1px solid " line) :border-radius "16px"
                 :box-shadow "0 8px 28px rgba(0, 0, 0, 0.08)"}}
   (panel-top ready? experiments changes query)
   (when ready?
     [:div {:style {:overflow "auto" :min-height "0" :flex "1"
                    :padding "0 14px 16px"}}
      (experiment-list state experiments changes)
      (panel-actions changes)])])

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

(defn toggle-pin
  "Adds a gate id at the front, or removes it."
  [pins id]
  (if (some #(= % id) pins)
    (filterv #(not= % id) pins)
    (into [id] pins)))

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
      (let [{:page/keys [sent experiments changes pins open-ids]} (first args)
            first-load? (not (:page/ready? state))]
        (when (and (seq experiments)
                   (or first-load?
                       (> (count experiments) (count (:page/experiments state)))))
          (with-render (cond-> (assoc state
                                      :page/ready? true
                                      :page/open? (if (:page/ready? state)
                                                    (:page/open? state)
                                                    true)
                                      :page/started? true
                                      :page/saw-statsig? true
                                      :page/watch nil
                                      :page/sent sent
                                      :page/experiments experiments
                                      :page/changes (or changes {}))
                         first-load? (assoc :page/pins (or pins [])
                                            :page/open-ids (or open-ids #{})))
                       [[:page/fx.clear-watch (:page/watch state)]
                        [:page/fx.honor sent changes]])))

      :page/ax.kick
      (if (:page/started? state)
        {:uf/db state
         :uf/fxs [[:page/fx.install] [:panel/fx.read]]
         :uf/dxs [[:page/ax.remembered :uf/prev-result]]}
        {:uf/db state
         :uf/fxs [[:page/fx.install] [:page/fx.probe]]
         :uf/dxs [[:page/ax.probed :uf/prev-result]]})

      :page/ax.remembered
      (with-render (merge state (first args) {:page/open? true}) [])

      :page/ax.saw-statsig
      (let [db (assoc state :page/saw-statsig? true)]
        (if (:page/started? state)
          {:uf/db db
           :uf/fxs [[:page/fx.load (:page/sent db)]]
           :uf/dxs [[:page/ax.loaded :uf/prev-result]]}
          {:uf/db db
           :uf/fxs [[:page/fx.probe]]
           :uf/dxs [[:page/ax.probed :uf/prev-result]]}))

      :page/ax.look
      (cond
        (and (:page/started? state) (:page/watch state))
        {:uf/db (assoc state :page/watch nil)
         :uf/fxs [[:page/fx.clear-watch (:page/watch state)]]}

        (:page/started? state)
        nil

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
          (let [db* (assoc db :page/started? true :page/watch nil)
                fxs (cond-> []
                      (:page/watch state) (conj [:page/fx.clear-watch (:page/watch state)])
                      true (conj [:page/fx.load (:page/sent state)]))]
            {:uf/db db*
             :uf/fxs fxs
             :uf/dxs [[:page/ax.loaded :uf/prev-result]]})

          (:page/watch state)
          {:uf/db db}

          :else
          {:uf/fxs [[:page/fx.arm]]
           :uf/dxs [[:page/ax.armed :uf/prev-result]]}))

      :page/ax.armed
      (let [timer (first args)]
        (if (:page/started? state)
          {:uf/fxs [[:page/fx.clear-watch timer]]}
          {:uf/db (assoc state :page/watch timer)}))

      :page/ax.expire
      (let [timer (first args)]
        (if (= timer (:page/watch state))
          {:uf/db (assoc state :page/watch nil)
           :uf/fxs [[:page/fx.clear-watch timer]]}
          nil))

      :page/ax.query
      (with-render (assoc state :page/query (or (first args) "")) [])

      :page/ax.toggle
      (let [open (:page/open-ids state #{})
            open (if (contains? open (first args))
                   (disj open (first args))
                   (conj open (first args)))
            db (assoc state :page/open-ids open)]
        (with-render db [[:panel/fx.write (or (:page/pins db) []) open]]))

      :page/ax.pin
      (let [pins (toggle-pin (or (:page/pins state) []) (first args))
            db (assoc state :page/pins pins)]
        (with-render db [[:panel/fx.write pins (:page/open-ids db)]]))

      :uf/unhandled-ax)))

(defn honor!
  "Copies original assignments, then applies overrides."
  [_dispatch [sent changes]]
  (when-let [live (live-statsig)]
    (when-let [original (or sent
                            (.-__pezPageChoicesWire js/window)
                            (bootstrap-statsig))]
      (copy-assignments! original live))
    (when (seq changes)
      (apply-changes! live changes))))

(defn parse-json [original text reviver]
  (if (undefined? reviver)
    (.call original js/JSON text)
    (.call original js/JSON text reviver)))

(defn with-saved-overrides
  "Remembers the wire payload, then applies saved overrides."
  [dispatch value]
  (when-let [payload (or (.-statsigPayload value)
                         (when (and (some? value)
                                    (.-feature_gates value)
                                    (or (.-dynamic_configs value)
                                        (.-layer_configs value)))
                           value))]
    (let [n (reduce + 0 (map #(count (object-entries (aget payload %))) value-buckets))
          prior (.-__pezPageChoicesWireSize js/window)]
      (when (or (nil? prior) (> n prior))
        (set! (.-__pezPageChoicesWire js/window)
              (.call (original-parse) js/JSON (js/JSON.stringify payload)))
        (set! (.-__pezPageChoicesWireSize js/window) n)))
    (apply-changes! payload (read-changes))
    (js/setTimeout #(dispatch [[:page/ax.saw-statsig]]) 0))
  value)

(defn install-rewrite!
  "Rewrites parsed JSON when it carries a Statsig payload."
  [dispatch _args]
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

(defn clone-values
  [values]
  (.call (original-parse) js/JSON (js/JSON.stringify values)))

(defn read-panel
  "Pins and open headers saved for this site."
  []
  (if-let [raw (.getItem js/localStorage panel-storage-key)]
    (let [data (js->clj (js/JSON.parse raw))]
      {:page/pins (vec (get data "pins"))
       :page/open-ids (set (get data "open"))})
    {:page/pins []
     :page/open-ids #{}}))

(defn load-page!
  "Reads the full switch set. Overrides come from localStorage."
  [_dispatch [sent]]
  (let [wire (.-__pezPageChoicesWire js/window)
        source (or wire
                   (let [live (when-let [values (live-statsig)] (clone-values values))
                         boot (bootstrap-statsig)
                         fuller (fn [a b]
                                  (let [n (fn [payload]
                                            (reduce + 0
                                                    (map #(count (object-entries (aget payload %)))
                                                         value-buckets)))]
                                    (if (>= (n a) (n b)) a b)))]
                     (cond
                       (and live boot) (fuller live boot)
                       live live
                       boot boot
                       :else sent)))]
    (merge {:page/sent source
            :page/experiments (when source (read-experiments source))
            :page/changes (read-changes)}
           (read-panel))))

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

(defn write-panel!
  "Saves pins and which headers are open."
  [_dispatch [pins open-ids]]
  (if (and (empty? pins) (empty? open-ids))
    (.removeItem js/localStorage panel-storage-key)
    (.setItem js/localStorage panel-storage-key
              (js/JSON.stringify (clj->js {:pins (vec pins)
                                           :open (vec open-ids)})))))

(def effect-handlers
  {:ui/fx.render render-ui!
   :storage/fx.write write-storage!
   :panel/fx.read read-panel
   :panel/fx.write write-panel!
   :page/fx.honor honor!
   :page/fx.load load-page!
   :page/fx.reload reload-page!
   :page/fx.install install-rewrite!
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
         enriched (mapv #(enrich-from-event replicant-data %) actions)
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
