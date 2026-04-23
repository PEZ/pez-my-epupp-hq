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
   :touch-chance 0.1
   :run-chance 0.3
   :jump-chance 0.08
   :gravity 0.5
   :terminal-vel 12
   :jump-vy -8})

;; -- Helpers --

(defn rand-between [lo hi]
  (+ lo (rand-int (- hi lo))))

;; -- State (single access point: dispatch!) --

(defn floor-y []
  (- js/window.innerHeight 40 (* (:h sprites/frame-size) (:scale config))))

(defonce !state (atom nil))

;; -- Forward declaration for dispatch (only legitimate use) --

(declare dispatch!)

;; -- Effects (imperative shell — no @!state reads) --

(defn perform-effect!
  "Execute a side effect described by an effect vector."
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

      :dom/fx.set-transform
      (let [[node x y] args]
        (when node
          (aset (.-style node) "transform"
                (str "translate3d(" x "px," y "px,0)")))
        nil)

      :dom/fx.cancel-raf
      (let [[id] args]
        (when id (js/cancelAnimationFrame id))
        nil)

      :dom/fx.add-click-handler
      (let [[node] args]
        (when node
          (set! (.-pointerEvents (.-style node)) "auto")
          (set! (.-cursor (.-style node)) "pointer")
          (.addEventListener node "click"
                             (fn [e]
                               (.stopPropagation e)
                               (dispatch! [[:buddy/ax.enter-state :being-hit]]))))
        nil)

      :dom/fx.add-mouse-tracker
      (let [!last-move (atom 0)
            handler (fn [e]
                      (let [now (js/Date.now)]
                        (when (> (- now @!last-move) 200)
                          (reset! !last-move now)
                          (dispatch! [[:buddy/ax.assoc :mouse-x (.-clientX e) :mouse-y (.-clientY e)]]))))]
        (.addEventListener js/document "mousemove" handler)
        (dispatch! [[:buddy/ax.assoc :mouse-handler handler]])
        nil)

      :dom/fx.remove-mouse-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/document "mousemove" handler))
        nil)

      :dom/fx.add-click-tracker
      (let [handler (fn [e]
                      (dispatch! [[:buddy/ax.react-to-click (.-clientX e) (.-clientY e)]]))]
        (.addEventListener js/document "click" handler)
        (dispatch! [[:buddy/ax.assoc :click-handler handler]])
        nil)

      :dom/fx.remove-click-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/document "click" handler))
        nil)

      :dom/fx.add-scroll-tracker
      (let [!last-scroll-y (atom (.-scrollY js/window))
            !last-scroll-time (atom (js/Date.now))
            handler (fn []
                      (let [now (js/Date.now)
                            curr-y (.-scrollY js/window)
                            dt (- now @!last-scroll-time)
                            dy (js/Math.abs (- curr-y @!last-scroll-y))]
                        (reset! !last-scroll-y curr-y)
                        (reset! !last-scroll-time now)
                        (when (and (> dy 500) (< dt 200))
                          (dispatch! [[:buddy/ax.enter-state :being-hit]]))))]
        (.addEventListener js/window "scroll" handler #js {:passive true})
        (dispatch! [[:buddy/ax.assoc :scroll-handler handler]])
        nil)

      :dom/fx.remove-scroll-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/window "scroll" handler))
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
     "  top: 0;"
     "  left: 0;"
     "  will-change: transform;"
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

(defn position-fxs
  "Effect vectors to update container position via translate3d."
  [container x y]
  [[:dom/fx.set-transform container x y]])

(defn pick-next-behavior
  "Pure behavior selection from a random roll."
  [roll]
  (let [{:keys [sit-chance meow-chance touch-chance jump-chance]} config]
    (cond
      (< roll sit-chance) :sitting
      (< roll (+ sit-chance meow-chance)) :meowing
      (< roll (+ sit-chance meow-chance touch-chance)) :touching
      (< roll (+ sit-chance meow-chance touch-chance jump-chance)) :jumping
      :else (if (< (rand) (:run-chance config)) :running :walking))))

;; -- Actions (pure: state + uf-data + action → result) --

(defn mouse-facing-fxs
  "Returns facing update map if mouse position suggests different facing, nil otherwise."
  [state uf-data]
  (let [mouse-x (:mouse/x uf-data)]
    (when (and mouse-x (:x state) (:el state))
      (let [cat-center-x (+ (:x state) (/ (* (:w sprites/frame-size) (:scale config)) 2))
            should-face (if (< mouse-x cat-center-x) :left :right)]
        (when (not= should-face (:facing state))
          {:uf/db (assoc state :facing should-face)
           :uf/fxs (facing-fxs (:el state) should-face)})))))

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

      :running
      (let [[lo hi] (:walk-duration config)
            duration (rand-between lo hi)
            new-facing (if (< (:roll uf-data) 0.5) :left :right)]
        {:uf/db (assoc base-db
                       :state-end (+ now duration)
                       :facing new-facing)
         :uf/fxs (into (anim-fxs el :run)
                       (facing-fxs el new-facing))})

      :jumping
      (let [vx (if (= (:facing state) :right)
                 (* 0.5 (:walk-speed config))
                 (* -0.5 (:walk-speed config)))
            vy (:jump-vy config)]
        {:uf/db (assoc base-db :vx vx :vy vy)
         :uf/fxs (anim-fxs el :jump)})

      :falling
      {:uf/db (assoc base-db :vx 0 :vy 0)
       :uf/fxs (anim-fxs el :jump)}

      :landing
      {:uf/db (assoc base-db :state-end (+ now 500))
       :uf/fxs (anim-fxs el :stunned)}

      :being-hit
      {:uf/db (assoc base-db :state-end (+ now 400))
       :uf/fxs (anim-fxs el :being-hit)}

      :stunned
      {:uf/db (assoc base-db :state-end (+ now 800))
       :uf/fxs (anim-fxs el :stunned)}

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
  [state now]
  (let [{:keys [x y facing container el current-anim state-end]} state
        speed (if (= current-anim :run) (:run-speed config) (:walk-speed config))
        dx (if (= facing :left) (- speed) speed)
        new-x (+ x dx)
        max-x (- js/window.innerWidth (* (:w sprites/frame-size) (:scale config)))
        move-result (cond
                      (< new-x 0)
                      {:uf/db (assoc state :x 0 :facing :right)
                       :uf/fxs (into (position-fxs container 0 y)
                                     (facing-fxs el :right))
                       :uf/dxs [[:buddy/ax.enter-state :idle]]}

                      (> new-x max-x)
                      {:uf/db (assoc state :x max-x :facing :left)
                       :uf/fxs (into (position-fxs container max-x y)
                                     (facing-fxs el :left))
                       :uf/dxs [[:buddy/ax.enter-state :idle]]}

                      :else
                      {:uf/db (assoc state :x new-x)
                       :uf/fxs (position-fxs container new-x y)})]
    (if (and state-end (> now state-end))
      (update move-result :uf/dxs (fnil conj []) [:buddy/ax.enter-state :idle])
      move-result)))

(defn tick-jumping
  "Pure jumping/falling tick with gravity."
  [state]
  (let [{:keys [x y vx vy container]} state
        gravity (:gravity config)
        terminal-vel (:terminal-vel config)
        new-vy (min (+ vy gravity) terminal-vel)
        new-x (+ x vx)
        new-y (+ y new-vy)
        max-x (- js/window.innerWidth (* (:w sprites/frame-size) (:scale config)))
        fy (floor-y)
        clamped-x (max 0 (min new-x max-x))]
    (if (>= new-y fy)
      ;; Landed
      (let [land-state (if (> new-vy 6) :stunned :idle)]
        {:uf/db (assoc state :x clamped-x :y fy :vx 0 :vy 0)
         :uf/fxs (position-fxs container clamped-x fy)
         :uf/dxs [[:buddy/ax.enter-state land-state]]})
      ;; Still airborne
      {:uf/db (assoc state :x clamped-x :y new-y :vx vx :vy new-vy)
       :uf/fxs (position-fxs container clamped-x new-y)})))

(defn handle-action
  "Pure action handler. Returns {:uf/db :uf/fxs :uf/dxs} or nil."
  [state uf-data action]
  (let [[op & args] action
        now (:system/now uf-data)]
    (case op
      :buddy/ax.assoc
      {:uf/db (apply assoc state args)}

      :buddy/ax.init
      (let [[dom-refs] args
            init-x 100
            init-y (floor-y)]
        {:uf/db (merge dom-refs
                       {:facing :right
                        :buddy-state nil
                        :frame 0
                        :frame-count 0
                        :current-anim nil
                        :x init-x
                        :y init-y})
         :uf/fxs [[:dom/fx.inject-css (make-sprite-css)]
                  [:dom/fx.set-transform (:container dom-refs) init-x init-y]
                  [:dom/fx.add-click-handler (:el dom-refs)]
                  [:dom/fx.add-mouse-tracker]
                  [:dom/fx.add-click-tracker]
                  [:dom/fx.add-scroll-tracker]]
         :uf/dxs [[:buddy/ax.enter-state :idle]]})

      :buddy/ax.enter-state
      (let [[new-bstate] args
            result (enter-state-action state uf-data new-bstate)
            anim-key (case new-bstate
                       :idle :idle
                       :walking :walk
                       :running :run
                       :jumping :jump
                       :falling :jump
                       :landing :stunned
                       :being-hit :being-hit
                       :stunned :stunned
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
      (let [{:keys [buddy-state state-end state-timer]} state]
        (case buddy-state
          :idle
          (if (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state (pick-next-behavior (:roll uf-data))]]}
            (mouse-facing-fxs state uf-data))

          :walking
          (tick-walking state now)

          :running
          (tick-walking state now)

          (:jumping :falling)
          (tick-jumping state)

          :landing
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state :idle]]})

          :being-hit
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state :stunned]]})

          :stunned
          (when (and state-end (> now state-end))
            {:uf/dxs [[:buddy/ax.enter-state :idle]]})

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

      :buddy/ax.react-to-click
      (let [[click-x click-y] args
            {:keys [x y buddy-state el]} state
            cat-center-x (+ x (/ (* (:w sprites/frame-size) (:scale config)) 2))
            dx (- click-x cat-center-x)
            dy (- click-y (+ y (/ (* (:h sprites/frame-size) (:scale config)) 2)))
            dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))
            ground-state? (contains? #{:idle :walking :running :sitting :meowing :touching} buddy-state)]
        (when ground-state?
          (cond
            (and (< dist 200) (< (rand) 0.3))
            (let [run-facing (if (pos? dx) :left :right)]
              {:uf/db (assoc state :facing run-facing)
               :uf/fxs (facing-fxs el run-facing)
               :uf/dxs [[:buddy/ax.enter-state :being-hit]]})

            (< dist 500)
            (let [face-dir (if (pos? dx) :right :left)]
              (when (not= face-dir (:facing state))
                {:uf/db (assoc state :facing face-dir)
                 :uf/fxs (facing-fxs el face-dir)})))))

      :buddy/ax.stop
      (let [{:keys [raf-id container mouse-handler click-handler scroll-handler]} state]
        {:uf/db nil
         :uf/fxs [[:dom/fx.cancel-raf raf-id]
                  [:dom/fx.remove-mouse-tracker mouse-handler]
                  [:dom/fx.remove-click-tracker click-handler]
                  [:dom/fx.remove-scroll-tracker scroll-handler]
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
                                         :roll (rand)
                                         :mouse/x (:mouse-x state)
                                         :mouse/y (:mouse-y state)}
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

(defn make-raf-loop
  "Creates a requestAnimationFrame callback that drives sprite and state ticks."
  []
  (let [frame-interval (/ 1000 (:fps config))
        tick-interval 100
        !timing (atom {:last-ts nil :frame-accum 0 :tick-accum 0})]
    (fn raf-cb [timestamp]
      (let [{:keys [last-ts frame-accum tick-accum]} @!timing
            dt (if last-ts (min (- timestamp last-ts) 200) 0)
            fa (+ frame-accum dt)
            ta (+ tick-accum dt)
            actions (cond-> []
                      (>= fa frame-interval) (conj [:buddy/ax.advance-frame])
                      (>= ta tick-interval) (conj [:buddy/ax.tick]))]
        (reset! !timing {:last-ts timestamp
                         :frame-accum (if (>= fa frame-interval) (rem fa frame-interval) fa)
                         :tick-accum (if (>= ta tick-interval) (rem ta tick-interval) ta)})
        (when (seq actions)
          (dispatch! actions))
        (when @!state
          (dispatch! [[:buddy/ax.assoc :raf-id (js/requestAnimationFrame raf-cb)]]))))))

(defn stop! []
  (dispatch! [[:buddy/ax.stop]]))

(defn start! []
  (when (:raf-id @!state) (stop!))
  (let [container (js/document.createElement "div")
        el (js/document.createElement "div")]
    (set! (.-id container) "page-buddy-container")
    (set! (.-id el) "page-buddy")
    (.appendChild container el)
    (js/document.body.appendChild container)
    (dispatch! [[:buddy/ax.init {:el el :container container}]])
    (let [raf-cb (make-raf-loop)
          raf-id (js/requestAnimationFrame raf-cb)]
      (dispatch! [[:buddy/ax.assoc :raf-id raf-id]]))
    (js/console.log "Page buddy started! 🐱")))

(comment
  (start!)
  (stop!)
  (dispatch! [[:buddy/ax.enter-state :walking]])
  (dispatch! [[:buddy/ax.enter-state :running]])
  (dispatch! [[:buddy/ax.enter-state :jumping]])
  (dispatch! [[:buddy/ax.enter-state :being-hit]])
  (dispatch! [[:buddy/ax.enter-state :sitting]])
  (dispatch! [[:buddy/ax.enter-state :meowing]])
  (dispatch! [[:buddy/ax.enter-state :touching]])
  @!state
  (select-keys @!state [:mouse-x :mouse-y])
  :rcf)
