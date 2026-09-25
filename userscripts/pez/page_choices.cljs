{:epupp/script-name "pez/page_choices.cljs"
 :epupp/auto-run-match ["https://chatgpt.com/*" "https://chat.openai.com/*"]
 :epupp/description "See the A/B experiments on this page, and change the ones this visit is in."
 :epupp/run-at "document-start"
 :epupp/inject ["scittle://replicant.js"
                "epupp://epupp/ui.cljs"]}

(ns pez.page-choices
  (:require [clojure.string :as string]
            [epupp.ui :as ui]
            [replicant.dom :as r]))

(def storage-key "pez.page-choices")
(def panel-id "pez-page-choices")

(def ink "rgb(13, 13, 13)")
(def paper "#ffffff")
(def quiet "#6e6e6e")
(def line "#ececec")
(def page-face "-apple-system-body, ui-sans-serif, -apple-system, system-ui, \"Segoe UI\", Helvetica, Arial, sans-serif")
(defn close-icon
  "Codicon close mark, the same one Epupp uses."
  [& {:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size
         :height size
         :viewBox "0 0 16 16"
         :fill "currentColor"}
   [:path {:d "M8.70701 8.00001L12.353 4.35401C12.548 4.15901 12.548 3.84201 12.353 3.64701C12.158 3.45201 11.841 3.45201 11.646 3.64701L8.00001 7.29301L4.35401 3.64701C4.15901 3.45201 3.84201 3.45201 3.64701 3.64701C3.45201 3.84201 3.45201 4.15901 3.64701 4.35401L7.29301 8.00001L3.64701 11.646C3.45201 11.841 3.45201 12.158 3.64701 12.353C3.74501 12.451 3.87301 12.499 4.00101 12.499C4.12901 12.499 4.25701 12.45 4.35501 12.353L8.00101 8.70701L11.647 12.353C11.745 12.451 11.873 12.499 12.001 12.499C12.129 12.499 12.257 12.45 12.355 12.353C12.55 12.158 12.55 11.841 12.355 11.646L8.70901 8.00001H8.70701Z"}]])

(defonce !parse (atom (.-parse js/JSON)))
(defonce !state (atom {:ready? false :open? true :experiments [] :changes {}}))
(defonce !refresh (atom (fn [])))

(defn chevron
  "Codicon chevron. :pointing is :down or :right."
  [& {:keys [size pointing] :or {size 16 pointing :right}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size
         :height size
         :viewBox "0 0 16 16"
         :fill "currentColor"}
   [:path {:d (if (= pointing :down)
                "M3.14645 5.64645C3.34171 5.45118 3.65829 5.45118 3.85355 5.64645L8 9.79289L12.1464 5.64645C12.3417 5.45118 12.6583 5.45118 12.8536 5.64645C13.0488 5.84171 13.0488 6.15829 12.8536 6.35355L8.35355 10.8536C8.15829 11.0488 7.84171 11.0488 7.64645 10.8536L3.14645 6.35355C2.95118 6.15829 2.95118 5.84171 3.14645 5.64645Z"
                "M5.64645 3.14645C5.45118 3.34171 5.45118 3.65829 5.64645 3.85355L9.79289 8L5.64645 12.1464C5.45118 12.3417 5.45118 12.6583 5.64645 12.8536C5.84171 13.0488 6.15829 13.0488 6.35355 12.8536L10.8536 8.35355C11.0488 8.15829 11.0488 7.84171 10.8536 7.64645L6.35355 3.14645C6.15829 2.95118 5.84171 2.95118 5.64645 3.14645Z")}]])

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
  "Gives each decision a title, numbering later copies of a repeated label."
  [decisions]
  (let [counts (frequencies (map :group decisions))]
    (first
     (reduce (fn [[out seen] {:keys [group] :as decision}]
               (let [i (get seen group 1)]
                 [(conj out (assoc decision :title
                                   (if (and (> (get counts group) 1)
                                            (> i 1))
                                     (str group " \u00b7 " i)
                                     group)))
                  (assoc seen group (inc i))]))
             [[] {}]
             decisions))))

(defn read-experiments
  "Reads every experiment the page sent, marking the ones this visit is in."
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
                    {:id (str (.-name obj))
                     :group (.-group_name obj)
                     :in-effect? (boolean (and (.-is_experiment_active obj)
                                               (.-is_user_in_experiment obj)))
                     :settings (mapv (fn [j]
                                       (let [param (aget names j)
                                             site (aget value param)]
                                         {:param param
                                          :label (humanize param)
                                          :kind (setting-kind site)
                                          :site site}))
                                     (range (.-length names)))}))))
            (range (.-length keys)))))))

(defn read-changes
  "Returns saved changes, or an empty map."
  []
  (if-let [raw (.getItem js/localStorage storage-key)]
    (js->clj (.call @!parse js/JSON raw))
    {}))

(defn write-changes!
  "Saves changes. An empty map forgets them."
  [changes]
  (if (empty? changes)
    (.removeItem js/localStorage storage-key)
    (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js changes)))))

(defn apply-changes!
  "Writes saved changes into the page data ChatGPT is about to read."
  [statsig changes]
  (let [configs (.-dynamic_configs statsig)]
    (doseq [[id params] changes]
      (when-let [config (aget configs id)]
        (let [value (or (.-value config) (js-obj))]
          (doseq [[param value* ] params]
            (aset value param (clj->js value*)))
          (set! (.-value config) value)))))
  statsig)

(defn install-rewrite!
  "Rewrites page data on parse, once, before ChatGPT reads it."
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

(defn change-count
  "Counts saved setting changes."
  [changes]
  (reduce + 0 (map count (vals changes))))

(defn shown-value
  "Returns your change when you have one, otherwise the site value."
  [changes id param site]
  (get-in changes [id param] site))

(defn changed?
  "True when this setting differs from what the page sent."
  [changes id param site]
  (not= (shown-value changes id param site) site))

(defn status-line
  "One line of where the experiments and your changes stand."
  [ready? experiments changes]
  (let [editable (count (filter :in-effect? experiments))
        resting (- (count experiments) editable)
        changed (change-count changes)]
    (cond
      (not ready?) "Reading the experiments on this page."
      (zero? (count experiments)) "No experiments arrived with this page."
      (pos? changed) (str changed " changed. Reload to see the page use them.")
      :else (str editable " you can change. " resting " are not in effect this visit."))))

(defn put-change
  "Sets or clears one saved change, dropping it when it matches the site."
  [changes id param site next-value]
  (let [params (if (= next-value site)
                 (dissoc (get changes id) param)
                 (assoc (get changes id) param next-value))
        changes (if (seq params)
                  (assoc changes id params)
                  (dissoc changes id))]
    changes))

(defn remember-change!
  "Remembers one setting. Returns the changes now saved."
  [id param site next-value]
  (let [changes (put-change (:changes @!state) id param site next-value)]
    (write-changes! changes)
    (swap! !state assoc :changes changes)
    changes))

(defn reload!
  "Reloads after this click, so the page reads your changes."
  []
  (js/setTimeout #(.reload js/location) 50))

(defn restore-page!
  "Forgets every change and reloads the page's own choices."
  []
  (write-changes! {})
  (swap! !state assoc :changes {})
  (reload!))

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
  [on? label commit]
  [:button {:type "button"
            :role "switch"
            :aria-checked (boolean on?)
            :aria-label label
            :on {:click (fn [_] (commit (not on?)))}
            :style {:width "36px"
                    :height "22px"
                    :padding "2px"
                    :border "none"
                    :border-radius "999px"
                    :cursor "pointer"
                    :flex-shrink "0"
                    :display "flex"
                    :align-items "center"
                    :justify-content (if on? "flex-end" "flex-start")
                    :background (if on? ink "#e3e3e3")}}
   [:span {:style {:width "18px"
                   :height "18px"
                   :border-radius "999px"
                   :background paper
                   :display "block"}}]])

(defn setting-control
  "The control for one setting, or the sent value when this visit is not in it."
  [commit editable? changes {:keys [id]} {:keys [param kind site]}]
  (let [current (shown-value changes id param site)]
    (if-not editable?
      [:span {:style {:color quiet :font-size "13px"}}
       (cond
         (true? current) "On"
         (false? current) "Off"
         :else (str current))]
      (case kind
        :bool (switch (boolean current) (humanize param)
                      (fn [next-value] (commit id param site next-value)))
        :text [:input {:type "text"
                       :value (str current)
                       :aria-label (humanize param)
                       :style (field-style)
                       :on {:change (fn [event]
                                      (commit id param site (.. event -target -value)))}}]
        :number [:input {:type "number"
                         :value (str current)
                         :aria-label (humanize param)
                         :style (field-style)
                         :on {:change (fn [event]
                                        (let [parsed (js/parseFloat (.. event -target -value))]
                                          (when-not (js/Number.isNaN parsed)
                                            (commit id param site parsed))))}}]
        [:span {:style {:color quiet :font-size "13px"}} "Left as sent"]))))

(defn setting-row
  [commit editable? changes experiment setting]
  (let [{:keys [id]} experiment
        {:keys [param label site]} setting
        edited? (and editable? (changed? changes id param site))
        sent (cond
               (true? site) "on"
               (false? site) "off"
               (string? site) (str "\u201c" site "\u201d")
               :else (str site))]
    [:div {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :gap "12px"}}
     [:span {:style {:display "flex" :flex-direction "column" :gap "2px" :min-width "0"}}
      [:span label]
      (when edited?
        [:span {:style {:color quiet :font-size "12px"}}
         (str "Page sent " sent)])]
     [:span {:style {:display "flex" :align-items "center" :gap "8px" :flex-shrink "0"}}
      (when edited?
        [:button {:type "button"
                  :on {:click (fn [_] (commit id param site site))}
                  :style {:font "inherit"
                          :font-size "12px"
                          :color quiet
                          :background "transparent"
                          :border "none"
                          :padding "0"
                          :cursor "pointer"
                          :text-decoration "underline"
                          :text-underline-offset "2px"}}
         "Reset"])
      (setting-control commit editable? changes experiment setting)]]))

(defn experiment-block
  [commit editable? changes {:keys [title settings] :as experiment}]
  [:section {:style {:display "flex" :flex-direction "column" :gap "8px"}}
   [:h2 {:style {:margin "0"
                 :font-family page-face
                 :font-size "16px"
                 :font-weight "600"
                 :line-height "1.3"
                 :color ink}}
    title]
   (if (seq settings)
     [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
      (for [setting settings]
        ^{:key (:param setting)}
        (setting-row commit editable? changes experiment setting))]
     [:p {:style {:margin "0" :color quiet}} "No settings in this experiment."])])

(defn text-button
  [label action]
  [:button {:type "button"
            :on {:click (fn [_] (action))}
            :style {:font-family "inherit"
                    :font-size "14px"
                    :color ink
                    :background "transparent"
                    :border "none"
                    :padding "0"
                    :cursor "pointer"
                    :text-decoration "underline"
                    :text-underline-offset "3px"}}
   label])

(defn group-open?
  "A group is open unless the visitor has closed it. Not-in-effect starts closed."
  [open-groups group]
  (contains? (or open-groups #{:editable}) group))

(defn disclosure
  "A section header that opens and closes its experiments."
  [open? title count on-toggle]
  [:button {:type "button"
            :aria-expanded open?
            :on {:click (fn [_] (on-toggle))}
            :style {:display "flex"
                    :align-items "center"
                    :gap "8px"
                    :width "100%"
                    :margin "16px 0 0"
                    :padding "10px 0"
                    :border "none"
                    :border-top (str "1px solid " line)
                    :background "transparent"
                    :color ink
                    :cursor "pointer"
                    :font "inherit"
                    :font-size "15px"
                    :font-weight "600"
                    :line-height "1.2"
                    :text-align "left"}}
   (chevron :pointing (if open? :down :right) :size 14)
   [:span {:style {:flex "1"}} title]
   [:span {:style {:color quiet :font-weight "500" :font-size "13px"}} (str count)]])

(defn panel
  [{:keys [ready? open? open-groups experiments changes]} {:keys [commit reload restore hide toggle-group]}]
  (when open?
    (let [editable (with-titles (filterv :in-effect? experiments))
          resting (with-titles (filterv (complement :in-effect?) experiments))
          editable-open? (group-open? open-groups :editable)
          resting-open? (group-open? open-groups :resting)]
      [:div {:style {:position "fixed"
                     :top "12px"
                     :right "12px"
                     :z-index "2147483646"
                     :width "340px"
                     :max-height "calc(100vh - 24px)"
                     :overflow "auto"
                     :box-sizing "border-box"
                     :padding "12px 14px 16px"
                     :background paper
                     :color ink
                     :font-family page-face
                     :font-size "14px"
                     :line-height "1.4"
                     :border (str "1px solid " line)
                     :border-radius "16px"
                     :box-shadow "0 8px 28px rgba(0, 0, 0, 0.08)"}}
       [:div {:style {:display "flex" :align-items "flex-start" :justify-content "space-between" :gap "8px"}}
        [:div {:style {:min-width "0" :flex "1"}}
         (ui/epupp-header :size 22 :title "Active A/B experiments" :tagline false)]
        [:button {:type "button"
                  :aria-label "Close"
                  :on {:click (fn [_] (hide))}
                  :style {:width "28px"
                          :height "28px"
                          :padding "0"
                          :border "none"
                          :border-radius "8px"
                          :background "transparent"
                          :color ink
                          :cursor "pointer"
                          :display "flex"
                          :align-items "center"
                          :justify-content "center"
                          :flex-shrink "0"}}
         (close-icon :size 16)]]
       [:p {:style {:margin "12px 0 0" :color quiet}}
        (status-line ready? experiments changes)]
       (when (and ready? (seq editable))
         [:div
          (disclosure editable-open? "You can change these" (count editable)
                      (fn [] (toggle-group :editable)))
          (when editable-open?
            [:div {:style {:display "flex" :flex-direction "column" :gap "16px" :margin-top "8px"}}
             (for [experiment editable]
               ^{:key (:id experiment)}
               (experiment-block commit true changes experiment))])])
       (when (and ready? (seq resting))
         [:div
          (disclosure resting-open? "Not in effect this visit" (count resting)
                      (fn [] (toggle-group :resting)))
          (when resting-open?
            [:div {:style {:display "flex" :flex-direction "column" :gap "16px" :margin-top "4px"}}
             [:p {:style {:margin "0" :color quiet}}
              "ChatGPT sent these with the page, and this visit is not part of them. They do not change what you see."]
             (for [experiment resting]
               ^{:key (:id experiment)}
               (experiment-block commit false changes experiment))])])
       (when ready?
         [:div {:style {:display "flex" :gap "16px" :margin-top "20px"}}
          (text-button "Reload" reload)
          (when (pos? (change-count changes))
            (text-button "Put the page's experiments back" restore))])])))

(def toolbar-id "pez-page-choices-toggle")

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

(defn paint-toolbar! []
  (when-let [button (js/document.getElementById toolbar-id)]
    (set! (.. button -style -background)
          (if (:open? @!state) "rgba(13, 13, 13, 0.06)" "transparent"))))

(defn ensure-toolbar! []
  (if-let [button (js/document.getElementById toolbar-id)]
    (do
      (when-let [host (toolbar-host)]
        (when-not (identical? (.-parentElement button) host)
          (.appendChild host button)))
      (paint-toolbar!))
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
                             (swap! !state update :open? not)
                             (@!refresh)))
        (.appendChild host button)
        (r/render button (ui/epupp-icon :size 22))
        (paint-toolbar!)
        (when-not (.-__pezPageChoicesWatch host)
          (let [observer (js/MutationObserver.
                          (fn [_ _]
                            (when-not (js/document.getElementById toolbar-id)
                              (ensure-toolbar!))))]
            (set! (.-__pezPageChoicesWatch host) true)
            (.observe observer host #js {:childList true})))))))

(defn render! []
  (reset! !refresh render!)
  (when-let [root (js/document.getElementById panel-id)]
    (r/render root
              (or (panel @!state
                         {:commit (fn [id param site next-value]
                                    (remember-change! id param site next-value)
                                    (render!))
                          :reload reload!
                          :restore restore-page!
                          :toggle-group (fn [group]
                                          (swap! !state update :open-groups
                                                 (fn [groups]
                                                   (let [groups (or groups #{:editable})]
                                                     (if (contains? groups group)
                                                       (disj groups group)
                                                       (conj groups group)))))
                                          (render!))
                          :hide (fn []
                                  (swap! !state assoc :open? false)
                                  (render!))})
                  [:span])))
  (ensure-toolbar!))

(defn ensure-root! []
  (or (js/document.getElementById panel-id)
      (let [el (js/document.createElement "div")]
        (set! (.-id el) panel-id)
        (.appendChild js/document.body el)
        el)))

(defn site-statsig
  "The page data as sent, read without applying saved changes."
  []
  (when-let [el (js/document.getElementById "client-bootstrap")]
    (let [value (.call @!parse js/JSON (.-textContent el))]
      (.-statsigPayload value))))

(defn pull! []
  (if-let [statsig (site-statsig)]
    (swap! !state assoc
           :ready? true
           :experiments (read-experiments statsig)
           :changes (read-changes))
    (swap! !state assoc :ready? false :experiments []))
  (when js/document.body
    (ensure-root!)
    (render!)))

(defn watch-bootstrap! []
  (when js/document.documentElement
    (let [!observer (atom nil)
          observer (js/MutationObserver.
                    (fn [_ _]
                      (when (js/document.getElementById "client-bootstrap")
                        (when-let [current @!observer]
                          (.disconnect current))
                        (pull!))))]
      (reset! !observer observer)
      (.observe observer js/document.documentElement #js {:childList true :subtree true}))))

(install-rewrite!)

(defn boot! []
  (pull!)
  (when-not (js/document.getElementById "client-bootstrap")
    (watch-bootstrap!)))

(if js/document.body
  (boot!)
  (js/document.addEventListener "DOMContentLoaded" boot!))
