{:epupp/script-name "pez/element_printing.cljs"
 :epupp/description "Isolate any element on a web page for clean printing"}


(ns pez.element-printing)


;; --- State ---


(defonce !state
  (atom {:mode :idle
         :handlers {}
         :highlighted-el nil
         :isolated-el nil
         :hidden-els []
         :saved-ancestor-styles []
         :original-body-padding nil
         :control-bar nil
         :print-style nil}))


(defn idle? [] (= :idle (:mode @!state)))
(defn hovering? [] (= :hovering (:mode @!state)))
(defn isolated? [] (= :isolated (:mode @!state)))


(def skip-tags #{"SCRIPT" "STYLE" "LINK"})


;; --- Highlight ---


(defn clear-highlight! []
  (when-let [el (:highlighted-el @!state)]
    (set! (.. el -style -outline) "")
    (set! (.. el -style -cursor) "")
    (swap! !state assoc :highlighted-el nil)))


(defn highlight-el! [el]
  (when (and el (not= el js/document.body) (not= el js/document.documentElement))
    (clear-highlight!)
    (set! (.. el -style -outline) "2px solid cyan")
    (set! (.. el -style -outlineOffset) "-2px")
    (set! (.. el -style -cursor) "crosshair")
    (swap! !state assoc :highlighted-el el)))


(defn on-mouseover [e]
  (.stopPropagation e)
  (highlight-el! (.-target e)))


;; --- DOM isolation ---


(defn isolate-element! [el]
  (let [hidden (atom [])]
    (loop [current el]
      (let [parent (.-parentElement current)]
        (when (and parent (not= current js/document.documentElement))
          (doseq [sibling (array-seq (.-children parent))]
            (when (and (not= sibling current)
                       (not (skip-tags (.-tagName sibling)))
                       (not= "none" (.. sibling -style -display)))
              (swap! hidden conj {:el sibling :display (.. sibling -style -display)})
              (set! (.. sibling -style -display) "none")))
          (when (not= parent js/document.body)
            (recur parent)))))
    @hidden))


(def ^:private style-props ["overflow" "overflow-y" "max-height" "height"])


(defn- save-style-props [style]
  (into {} (map (fn [prop]
                  [prop {:value (.getPropertyValue style prop)
                         :priority (.getPropertyPriority style prop)}])
                style-props)))


(defn- set-overflow-props! [style overflow overflowY]
  (.setProperty style "overflow" overflow "important")
  (.setProperty style "overflow-y" overflowY "important")
  (.setProperty style "max-height" "none" "important")
  (.setProperty style "height" "auto" "important"))


(defn- restore-style-props! [style saved-props]
  (doseq [prop style-props]
    (let [{:keys [value priority]} (get saved-props prop)]
      (if (= "" value)
        (.removeProperty style prop)
        (.setProperty style prop value priority)))))


(defn reset-ancestor-overflow! [el]
  (let [saved (atom [])]
    (loop [current (.-parentElement el)]
      (when current
        (let [style (.-style current)
              props (save-style-props style)]
          (swap! saved conj {:el current :props props})
          (if (or (= current js/document.body) (= current js/document.documentElement))
            (set-overflow-props! style "auto" "auto")
            (set-overflow-props! style "visible" "visible")))
        (when (not= current js/document.documentElement)
          (recur (.-parentElement current)))))
    (swap! !state assoc :saved-ancestor-styles @saved)))


(defn restore-page! []
  (doseq [{:keys [el display]} (:hidden-els @!state)]
    (set! (.. el -style -display) (or display "")))
  (doseq [{:keys [el props]} (:saved-ancestor-styles @!state)]
    (restore-style-props! (.-style el) props))
  (when-let [padding (:original-body-padding @!state)]
    (set! (.. js/document.body -style -paddingTop) padding))
  (when-let [bar (:control-bar @!state)]
    (.remove bar))
  (when-let [style (:print-style @!state)]
    (.remove style))
  (swap! !state assoc
         :mode :idle
         :hidden-els []
         :saved-ancestor-styles []
         :isolated-el nil
         :control-bar nil
         :print-style nil
         :original-body-padding nil))


(declare cancel!)


;; --- Control bar ---


(defn create-button [text on-click-fn]
  (let [btn (js/document.createElement "button")]
    (set! (.-textContent btn) text)
    (set! (.. btn -style -cssText)
          (str "padding: 6px 16px; margin: 0 6px; border: 1px solid rgba(255,255,255,0.3);"
               "border-radius: 4px; background: rgba(255,255,255,0.15); color: white;"
               "cursor: pointer; font-size: 14px;"))
    (.addEventListener btn "click" on-click-fn)
    btn))


(defn add-print-style! []
  (let [style (js/document.createElement "style")]
    (set! (.-textContent style)
          "@media print { #epupp-print-bar { display: none !important; } body { padding-top: 0 !important; } }")
    (.appendChild js/document.head style)
    (swap! !state assoc :print-style style)))


(defn show-control-bar! []
  (let [bar (js/document.createElement "div")]
    (set! (.-id bar) "epupp-print-bar")
    (set! (.. bar -style -cssText)
          (str "position: fixed; top: 0; left: 0; right: 0; z-index: 2147483647;"
               "background: rgba(30,41,59,0.95); color: white; padding: 8px 16px;"
               "display: flex; align-items: center; justify-content: flex-end;"
               "gap: 8px; font-family: system-ui, sans-serif; font-size: 14px;"
               "box-shadow: 0 2px 8px rgba(0,0,0,0.3);"))
    (let [label (js/document.createElement "span")]
      (set! (.-textContent label) "Element isolated for printing")
      (set! (.. label -style -marginRight) "auto")
      (.appendChild bar label))
    (.appendChild bar (create-button "Cancel" (fn [_] (cancel!))))
    (.appendChild bar (create-button "Print" (fn [_] (.print js/window))))
    (.appendChild js/document.body bar)
    (swap! !state assoc
           :original-body-padding (.. js/document.body -style -paddingTop)
           :control-bar bar)
    (let [bar-height (.-offsetHeight bar)]
      (set! (.. js/document.body -style -paddingTop) (str bar-height "px")))
    (add-print-style!)))


;; --- Isolation flow ---


(defn do-isolate! [el]
  (let [hidden (isolate-element! el)]
    (swap! !state assoc
           :hidden-els hidden
           :isolated-el el
           :mode :isolated)
    (reset-ancestor-overflow! el)
    (show-control-bar!)))


;; --- Hover mode ---


(defn stop-hover! []
  (when (hovering?)
    (let [{:keys [handlers]} @!state]
      (when-let [mo (:mouseover handlers)]
        (.removeEventListener js/document "mouseover" mo true))
      (when-let [cl (:click handlers)]
        (.removeEventListener js/document "click" cl true)))
    (clear-highlight!)
    (swap! !state assoc :mode :idle :handlers {})))


(defn on-click [e]
  (.stopPropagation e)
  (.preventDefault e)
  (let [el (.-target e)]
    (when (and el (not= el js/document.body) (not= el js/document.documentElement))
      (clear-highlight!)
      (stop-hover!)
      (do-isolate! el))))


(defn start-hover! []
  (cond
    (hovering?) (stop-hover!)
    (isolated?) nil
    :else
    (do
      (.addEventListener js/document "mouseover" on-mouseover true)
      (.addEventListener js/document "click" on-click true)
      (swap! !state assoc
             :mode :hovering
             :handlers {:mouseover on-mouseover
                        :click on-click}))))


;; --- Cancel ---


(defn cancel! []
  (restore-page!)
  (start-hover!))


;; --- Keyboard ---


(defn on-keydown [e]
  (when (= "Escape" (.-key e))
    (.preventDefault e)
    (.stopPropagation e)
    (cond
      (isolated?) (cancel!)
      (hovering?) (stop-hover!))))


(defonce escape-handler
  (do (.addEventListener js/document "keydown" on-keydown true)
      on-keydown))


;; --- Entry point ---


(defn start! []
  (start-hover!))


(start!)