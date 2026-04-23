(ns page-buddy
  (:require [page-buddy-sprites :as sprites]))

;; -- Configuration --

(def config
  {:scale 3
   :fps 8
   :walk-speed 2
   :run-speed 4
   :idle-duration [3000 8000]
   :walk-duration [2000 6000]
   :sit-chance 0.3
   :sleep-delay 5000
   :meow-chance 0.15
   :touch-chance 0.1})

;; -- Helpers --

(defn rand-between [lo hi]
  (+ lo (rand-int (- hi lo))))

;; -- State (single access point: dispatch!) --

(defonce !state (atom nil))

;; -- Forward declaration for dispatch (only legitimate use) --

(declare dispatch!)

;; -- Effects (imperative shell — no @!state reads) --

(defn perform-effect!
  "Execute a side effect. Returns a value for :uf/prev-result threading."
  [effect]
  (let [[op & args] effect]
    (case op
      :dom/fx.create-buddy
      (let [container (js/document.createElement "div")
            el (js/document.createElement "div")]
        (set! (.-id container) "page-buddy-container")
        (set! (.-id el) "page-buddy")
        (.appendChild container el)
        (js/document.body.appendChild container)
        {:container container :el el})

      :dom/fx.inject-css
      (let [[css] args
            style (js/document.createElement "style")]
        (set! (.-textContent style) css)
        (js/document.head.appendChild style)
        nil)

      :dom/fx.remove-element
      (let [[node] args]
        (when node (.remove node))
        nil)

      :dom/fx.set-style
      (let [[node prop value] args]
        (when node
          (aset (.-style node) prop value))
        nil)

      :dom/fx.set-class
      (let [[node class-name add?] args]
        (when node
          (if add?
            (.add (.-classList node) class-name)
            (.remove (.-classList node) class-name)))
        nil)

      :timer/fx.set-interval
      (let [[callback-action ms] args]
        (js/setInterval (fn [] (dispatch! [callback-action])) ms))

      :timer/fx.clear-interval
      (let [[timer-id] args]
        (when timer-id (js/clearInterval timer-id))
        nil)

      :log/fx.log
      (do (apply js/console.log args) nil)

      (do (js/console.warn "Unhandled effect:" (pr-str effect)) nil))))

;; -- Pure helpers for actions --

(defn make-sprite-css []
  (let [{:keys [w h]} sprites/frame-size
        s (:scale config)]
    (str
     "#page-buddy-container {"
     "  position: fixed;"
     "  bottom: 40px;"
     "  left: 100px;"
     "  z-index: 2147483647;"
     "  pointer-events: none;"
     "  image-rendering: pixelated;"
     "}"
     "#page-buddy {"
     "  width: " (* w s) "px;"
     "  height: " (* h s) "px;"
     "  background-size: auto " (* h s) "px;"
     "  background-repeat: no-repeat;"
     "  image-rendering: pixelated;"
     "}"
     "#page-buddy.facing-left {"
     "  transform: scaleX(-1);"
     "}")))

(defn anim-fxs
  "Effect vectors to apply a sprite animation to an element."
  [el anim-key]
  (let [{:keys [frames data]} (get sprites/animations anim-key)
        {:keys [w h]} sprites/frame-size
        s (:scale config)
        sheet-w (* w frames s)]
    [[:dom/fx.set-style el "backgroundImage" (str "url(" data ")")]
     [:dom/fx.set-style el "backgroundSize" (str sheet-w "px " (* h s) "px")]
     [:dom/fx.set-style el "backgroundPosition" "0px 0"]]))

(defn facing-fxs
  "Effect vectors to set facing direction on an element."
  [el direction]
  [[:dom/fx.set-class el "facing-left" (= direction :left)]])

(defn pick-next-behavior
  "Pure behavior selection from a random roll."
  [roll]
  (let [{:keys [sit-chance meow-chance touch-chance]} config]
    (cond
      (< roll sit-chance) :sitting
      (< roll (+ sit-chance meow-chance)) :meowing
      (< roll (+ sit-chance meow-chance touch-chance)) :touching
      :else :walking)))

;; -- Actions (pure: state + uf-data + action → result) --

(defn enter-state-action
  "Pure state transition. Returns {:uf/db :uf/fxs}."
  [state uf-data new-bstate]
  (let [el (:el state)
        now (:system/now uf-data)
        base-db (assoc state :buddy-state new-bstate :state-timer now)]
    (case new-bstate
      :idle
      (let [[lo hi] (:idle-duration config)
            duration (rand-between lo hi)]
        {:uf/db (assoc base-db :state-end (+ now duration))
         :uf/fxs (anim-fxs el :idle)})

      :walking
      (let [[lo hi] (:walk-duration config)
            duration (rand-between lo hi)
            new-facing (if (< (:roll uf-data) 0.5) :left :right)]
        {:uf/db (assoc base-db
                       :state-end (+ now duration)
                       :facing new-facing)
         :uf/fxs (into (anim-fxs el :walk)
                       (facing-fxs el new-facing))})

      :sitting
      {:uf/db base-db
       :uf/fxs (anim-fxs el :sit)}

      :sleeping
      {:uf/db (assoc base-db :state-end (+ now (:sleep-delay config)))
       :uf/fxs (anim-fxs el :sleep)}

      :meowing
      {:uf/db (assoc base-db :state-end (+ now 800))
       :uf/fxs (anim-fxs el :meow)}

      :touching
      {:uf/db (assoc base-db :state-end (+ now 800))
       :uf/fxs (anim-fxs el :touch)}

      nil)))

(defn tick-walking
  "Pure walking tick logic."
  [state now el]
  (let [{:keys [facing container current-anim state-end]} state
        x (js/parseFloat (.. container -style -left))
        speed (if (= current-anim :run)
                (:run-speed config)
                (:walk-speed config))
        dx (if (= facing :left) (- speed) speed)
        new-x (+ x dx)
        max-x (- js/window.innerWidth
                 (* (:w sprites/frame-size) (:scale config)))
        move-result (cond
                      (< new-x 0)
                      {:uf/db (assoc state :facing :right)
                       :uf/fxs (into [[:dom/fx.set-style container "left" "0px"]]
                                     (facing-fxs el :right))
                       :uf/dxs [[:buddy/ax.enter-state :idle]]}

                      (> new-x max-x)
                      {:uf/db (assoc state :facing :left)
                       :uf/fxs (into [[:dom/fx.set-style container "left" (str max-x "px")]]
                                     (facing-fxs el :left))
                       :uf/dxs [[:buddy/ax.enter-state :idle]]}

                      :else
                      {:uf/fxs [[:dom/fx.set-style container "left" (str new-x "px")]]})]
    (if (and state-end (> now state-end))
      (update move-result :uf/dxs (fnil conj []) [:buddy/ax.enter-state :idle])
      move-result)))

(defn handle-action
  "Pure action handler. Returns {:uf/db :uf/fxs :uf/dxs} or nil."
  [state uf-data action]
  (let [[op & args] action
        now (:system/now uf-data)]
    (case op
      :buddy/ax.init
      (let [[dom-refs] args]
        {:uf/db (merge dom-refs
                       {:facing :right
                        :buddy-state nil
                        :frame 0
                        :frame-count 0
                        :current-anim nil})
         :uf/fxs [[:dom/fx.inject-css (make-sprite-css)]]
         :uf/dxs [[:buddy/ax.enter-state :idle]]})

      :buddy/ax.enter-state
      (let [[new-bstate] args
            result (enter-state-action state uf-data new-bstate)
            anim-key (case new-bstate
                       :idle :idle
                       :walking :walk
                       :sitting :sit
                       :sleeping :sleep
                       :meowing :meow
                       :touching :touch
                       nil)]
        (when result
          (let [frames (get-in sprites/animations [anim-key :frames] 0)]
            (-> result
                (assoc-in [:uf/db :current-anim] anim-key)
                (assoc-in [:uf/db :frame] 0)
                (assoc-in [:uf/db :frame-count] frames)))))

      :buddy/ax.advance-frame
      (let [{:keys [el frame frame-count]} state]
        (when (and el (pos? frame-count))
          (let [{:keys [w]} sprites/frame-size
                s (:scale config)
                next-frame (mod (inc frame) frame-count)
                offset (* next-frame w s)]
            {:uf/db (assoc state :frame next-frame)
             :uf/fxs [[:dom/fx.set-style el "backgroundPosition"
                        (str "-" offset "px 0")]]})))

      :buddy/ax.tick
      (let [{:keys [buddy-state state-end state-timer el]} state]
        (case buddy-state
          :idle
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state (pick-next-behavior (:roll uf-data))]]})

          :walking
          (tick-walking state now el)

          :sitting
          (let [sit-frames (get-in sprites/animations [:sit :frames])
                elapsed (- now state-timer)]
            (when (> elapsed (* sit-frames (/ 1000 (:fps config))))
              {:uf/dxs [[:buddy/ax.enter-state :sleeping]]}))

          :sleeping
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state :idle]]})

          :meowing
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state :idle]]})

          :touching
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state :idle]]})

          nil))

      :buddy/ax.store-timers
      (let [[frame-timer state-timer] args]
        {:uf/db (assoc state
                       :animation-timer frame-timer
                       :state-update-timer state-timer)})

      :buddy/ax.stop
      (let [{:keys [animation-timer state-update-timer container]} state]
        {:uf/db nil
         :uf/fxs [[:timer/fx.clear-interval animation-timer]
                  [:timer/fx.clear-interval state-update-timer]
                  [:dom/fx.remove-element container]
                  [:log/fx.log "Page buddy stopped."]]})

      (do (js/console.warn "Unhandled action:" (pr-str action))
          nil))))

;; -- Dispatch Loop (single state access point) --

(defn dispatch!
  "The single access point for state. Only place that reads/writes !state."
  [actions]
  (loop [remaining (seq actions)
         state @!state
         all-fxs []
         all-dxs []]
    (if remaining
      (let [action (first remaining)
            result (handle-action state {:system/now (js/Date.now)
                                         :roll (rand)}
                                  action)
            new-state (if (and (map? result) (contains? result :uf/db))
                        (:uf/db result)
                        state)
            fxs (when (map? result) (:uf/fxs result))
            dxs (when (map? result) (:uf/dxs result))]
        (recur (next remaining)
               new-state
               (into all-fxs fxs)
               (into all-dxs dxs)))
      (do
        (reset! !state state)
        (doseq [fx all-fxs]
          (when fx
            (perform-effect! fx)))
        (when (seq all-dxs)
          (dispatch! all-dxs))))))

;; -- Lifecycle (entry points dispatch actions only) --

(defn stop! []
  (dispatch! [[:buddy/ax.stop]]))

(defn start! []
  (when (:animation-timer @!state) (stop!))
  (let [container (js/document.createElement "div")
        el (js/document.createElement "div")]
    (set! (.-id container) "page-buddy-container")
    (set! (.-id el) "page-buddy")
    (.appendChild container el)
    (js/document.body.appendChild container)
    (let [frame-timer (js/setInterval
                       (fn [] (dispatch! [[:buddy/ax.advance-frame]]))
                       (/ 1000 (:fps config)))
          state-timer (js/setInterval
                       (fn [] (dispatch! [[:buddy/ax.tick]]))
                       100)]
      (dispatch! [[:buddy/ax.init {:el el :container container}]
                  [:buddy/ax.store-timers frame-timer state-timer]])
      (js/console.log "Page buddy started! 🐱"))))

(comment
  (start!)
  (stop!)
  (dispatch! [[:buddy/ax.enter-state :walking]])
  (dispatch! [[:buddy/ax.enter-state :sitting]])
  (dispatch! [[:buddy/ax.enter-state :meowing]])
  (dispatch! [[:buddy/ax.enter-state :touching]])
  @!state
  :rcf)
