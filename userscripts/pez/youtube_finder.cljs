{:epupp/script-name "pez/youtube_finder.cljs"
 :epupp/auto-run-match "https://www.youtube.com/watch*"
 :epupp/description "Search YouTube captions and seek to matches"
 :epupp/inject ["scittle://replicant.js"
                "epupp://epupp/ui.cljs"]}

(ns pez.youtube-finder
  (:require [epupp.ui :as ui]
            [replicant.dom :as r]))

(defn get-transcript
  "Returns all transcript segments as a vector of {:time :text} maps.
   Requires the transcript panel to be open."
  []
  (let [segments (js/document.querySelectorAll "ytd-transcript-segment-renderer")]
    (mapv (fn [i]
            (let [el (.item segments i)]
              {:time (some-> (.querySelector el ".segment-timestamp") .-textContent .trim)
               :text (some-> (.querySelector el ".segment-text") .-textContent .trim)}))
          (range (.-length segments)))))

(defn search-transcript
  "Search transcript for segments matching a pattern (string or regex).
   Returns matching segments with their timestamps."
  [pattern]
  (let [re (if (instance? js/RegExp pattern)
             pattern
             (js/RegExp. pattern "i"))
        segments (get-transcript)]
    (filterv #(re-find re (:text %)) segments)))

(defn open-transcript!
  "Opens the transcript panel if not already open."
  []
  (if (pos? (.-length (js/document.querySelectorAll "ytd-transcript-segment-renderer")))
    :already-open
    (do (.click (js/document.querySelector "button[aria-label*='transcript' i]"))
        :opening)))

(defn parse-time
  "Parses a timestamp string like '1:17' or '1:02:30' into seconds."
  [time-str]
  (let [parts (mapv js/parseInt (.split time-str ":"))]
    (case (count parts)
      1 (first parts)
      2 (+ (* 60 (first parts)) (second parts))
      3 (+ (* 3600 (first parts)) (* 60 (second parts)) (nth parts 2))
      0)))

(defn seek-to!
  "Seeks the video to a given time. Accepts seconds (number) or a
   timestamp string like '1:17' or '1:02:30'."
  [time]
  (let [seconds (if (number? time) time (parse-time time))
        player (js/document.querySelector "#movie_player")]
    (.seekTo player seconds true)
    {:seeked-to seconds}))

(def gold "#FFDC73")
(def blue "#4A71C4")
(def bg-dark "oklch(0.18 0.008 85)")
(def bg-input "oklch(0.22 0.008 85)")
(def bg-hover "oklch(0.26 0.01 85)")
(def text-primary "oklch(0.92 0.01 85)")
(def text-muted "oklch(0.65 0.01 85)")
(def border-subtle "oklch(0.30 0.01 85)")

(def !state (atom {:panel-open? false
                   :query ""
                   :results nil}))

(def !mount (atom nil))

(declare render! do-search!)

(defn result-link [{:keys [time text]}]
  [:a {:replicant/key (str time "-" (hash text))
       :href "#"
       :style {:display "flex"
               :align-items "baseline"
               :gap "10px"
               :padding "5px 8px"
               :margin "0 -8px"
               :color text-primary
               :text-decoration "none"
               :font-size "12px"
               :line-height "1.4"
               :border-radius "4px"
               :transition "background 0.1s"}
       :on {:click (fn [e]
                     (.preventDefault e)
                     (seek-to! time)
                     (swap! !state assoc :panel-open? false)
                     (render!))
            :mouseenter (fn [e]
                          (set! (.. e -currentTarget -style -background) bg-hover))
            :mouseleave (fn [e]
                          (set! (.. e -currentTarget -style -background) "transparent"))}}
   [:span {:style {:color gold
                   :font-family "monospace"
                   :font-size "11px"
                   :white-space "nowrap"
                   :min-width "38px"}} time]
   [:span {:style {:color text-muted}} text]])

(defn finder-ui []
  (let [{:keys [panel-open? query results]} @!state
        stop-prop (fn [e] (.stopPropagation e))
        has-results? (seq results)]
    [:div {:style {:background bg-dark
                   :border-radius "8px"
                   :padding "8px 12px"
                   :margin "8px 0 4px"
                   :font-family "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"}}
     [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
      [:div {:style {:flex-shrink "0" :display "flex" :align-items "center"}}
       (ui/epupp-icon :size 18)]
      (if panel-open?
        (list
         [:input {:type "text"
                  :value (or query "")
                  :placeholder "Search captions\u2026"
                  :autofocus true
                  :style {:flex 1
                          :padding "4px 8px"
                          :background bg-input
                          :color text-primary
                          :border (str "1px solid " border-subtle)
                          :border-radius "4px"
                          :font-size "13px"
                          :font-family "inherit"
                          :outline "none"}
                  :on {:input (fn [e]
                                (swap! !state assoc :query (.. e -target -value))
                                (render!))
                       :keydown (fn [e]
                                  (.stopPropagation e)
                                  (when (= "Escape" (.-key e))
                                    (swap! !state assoc :panel-open? false)
                                    (render!))
                                  (when (= "Enter" (.-key e))
                                    (do-search!)))
                       :keyup stop-prop
                       :keypress stop-prop
                       :focus (fn [e]
                                (set! (.. e -target -style -borderColor) gold))
                       :blur (fn [e]
                               (set! (.. e -target -style -borderColor) border-subtle))}}]
         [:button {:style {:padding "4px 12px"
                           :background blue
                           :color "white"
                           :border "none"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "12px"
                           :font-family "inherit"
                           :font-weight "500"
                           :letter-spacing "0.02em"}
                   :on {:click (fn [_] (do-search!))}}
          "Find"]
         [:button {:style {:background "transparent"
                           :border "none"
                           :color text-muted
                           :cursor "pointer"
                           :font-size "14px"
                           :padding "2px 4px"
                           :line-height "1"}
                   :title "Close (Esc)"
                   :on {:click (fn [_]
                                 (swap! !state assoc :panel-open? false)
                                 (render!))}}
          "\u00D7"])
        [:button {:style {:background "transparent"
                          :border "none"
                          :color text-muted
                          :cursor "pointer"
                          :font-size "12px"
                          :font-family "inherit"
                          :padding "0"
                          :display "flex"
                          :align-items "center"
                          :gap "4px"}
                  :on {:click (fn [_]
                                (swap! !state assoc :panel-open? true)
                                (render!))}}
         [:span "Search captions"]])]
     (when (and panel-open? has-results?)
       [:div {:style {:margin-top "6px"
                      :padding-top "6px"
                      :border-top (str "1px solid " border-subtle)
                      :max-height "240px"
                      :overflow-y "auto"}}
        [:div {:style {:color text-muted
                       :font-size "10px"
                       :letter-spacing "0.04em"
                       :text-transform "uppercase"
                       :margin-bottom "4px"}}
         (str (count results) " match" (when (not= 1 (count results)) "es"))]
        (map result-link results)])]))

(defn render! []
  (when-let [el @!mount]
    (r/render el (finder-ui))))

(defn do-search! []
  (let [q (:query @!state)]
    (when (seq q)
      (open-transcript!)
      (let [results (search-transcript q)]
        (swap! !state assoc :results results)
        (when (= 1 (count results))
          (seek-to! (:time (first results)))
          (swap! !state assoc :panel-open? false))
        (render!)))))

(defn mount! []
  (let [old (js/document.getElementById "epupp-finder-mount")]
    (when old (.remove old)))
  (let [above-fold (js/document.getElementById "above-the-fold")
        title-div (js/document.getElementById "title")
        container (doto (js/document.createElement "div")
                    (.setAttribute "id" "epupp-finder-mount"))]
    (.insertBefore above-fold container title-div)
    (reset! !mount container)
    (render!)))

(mount!)

(comment
  (open-transcript!)
  (count (get-transcript))
  (take 3 (get-transcript))
  (search-transcript "simple")
  (search-transcript "i don't care how much")
  (search-transcript #"complect|easy|hard")
  (search-transcript "Clojure")

  (seek-to! "1:17")
  (seek-to! "40:14")
  (seek-to! 120)
  (let [hit (first (search-transcript "i don't care how much"))]
    (seek-to! (:time hit)))

  ;; Re-mount the UI (e.g. after redefining components)
  (mount!)
  :rcf)
