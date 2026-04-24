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

(def energy-rates
  {:walking  -0.002
   :running  -0.004
   :jumping  -0.003
   :falling  -0.001
   :sitting   0.003
   :sleeping  0.005
   :idle      0.001
   :meowing   0.0
   :touching  0.0
   :being-hit -0.005
   :stunned   0.0
   :landing   0.0
   :perching -0.002
   :edge-contemplating 0.001
   :dragging 0.0
   :cursor-chasing -0.002})

(defonce !state (atom nil))

(defonce !env (atom {:mouse-x nil :mouse-y nil :scroll-y 0}))

(defn rand-between [lo hi]
  (+ lo (rand-int (- hi lo))))

;; -- State (single access point: dispatch!) --

(defn update-energy
  "Pure energy update. Returns new energy clamped to [0.0, 1.0]."
  [energy buddy-state]
  (let [rate (get energy-rates buddy-state 0.0)]
    (max 0.0 (min 1.0 (+ energy rate)))))

(defn scan-surfaces-data
  "Scan DOM for viable perching surfaces. Returns vector of surface maps."
  []
  (let [vw js/window.innerWidth
        vh js/window.innerHeight
        cat-w (* (:w sprites/frame-size) (:scale config))
        candidates (js/document.querySelectorAll
                    "nav, header, aside, section, article, div, img, table, footer, main")
        results (atom [])]
    (.forEach candidates
              (fn [el]
                (when (and (not= (.-id el) "page-buddy-container")
                           (not= (.-id el) "page-buddy")
                           (.-isConnected el))
                  (let [rect (.getBoundingClientRect el)
                        w (.-width rect)
                        h (.-height rect)
                        top (.-top rect)
                        left (.-left rect)
                        bottom (.-bottom rect)
                        right (.-right rect)]
                    (when (and (> w cat-w)
                               (> h 20)
                               (< top vh)
                               (> bottom 0)
                               (< left vw)
                               (> right 0)
                               (> top 50)
                               (< top (- vh 100)))
                      (swap! results conj
                             {:el el
                              :top top
                              :left left
                              :right right
                              :bottom bottom
                              :width w
                              :height h}))))))
    @results))

(defn find-landing-surface
  "Check if cat's feet crossed through a surface top edge during fall."
  [surfaces cat-x cat-y cat-prev-y]
  (let [cat-w (* (:w sprites/frame-size) (:scale config))
        cat-h (* (:h sprites/frame-size) (:scale config))
        cat-right (+ cat-x cat-w)
        cat-bottom (+ cat-y cat-h)
        cat-prev-bottom (+ cat-prev-y cat-h)]
    (when (> cat-y cat-prev-y)
      (first
       (filter (fn [{:keys [top left right width]}]
                 (and (<= cat-prev-bottom top)
                      (>= cat-bottom top)
                      (< cat-x right)
                      (> cat-right left)
                      (> width cat-w)))
               surfaces)))))

(defn find-perch-target
  "Find best surface to jump onto from current position."
  [surfaces cat-x cat-y]
  (let [cat-w (* (:w sprites/frame-size) (:scale config))
        cat-center-x (+ cat-x (/ cat-w 2))
        max-jump-height 200
        max-horiz-dist 300]
    (->> surfaces
         (filter (fn [{:keys [top left width]}]
                   (let [surface-center-x (+ left (/ width 2))
                         horiz-dist (js/Math.abs (- surface-center-x cat-center-x))
                         height-diff (- cat-y top)]
                     (and (> height-diff 20)
                          (< height-diff max-jump-height)
                          (< horiz-dist max-horiz-dist)
                          (> width cat-w)))))
         (sort-by (fn [{:keys [top left width]}]
                    (let [surface-center-x (+ left (/ width 2))
                          horiz-dist (js/Math.abs (- surface-center-x cat-center-x))
                          height-diff (- cat-y top)]
                      (+ horiz-dist (* height-diff 0.5)))))
         first)))

(defn compute-jump-to-surface
  "Compute vx/vy to jump from (cat-x, cat-y) to land on surface top edge."
  [cat-x cat-y surface]
  (let [{:keys [top left right]} surface
        cat-w (* (:w sprites/frame-size) (:scale config))
        cat-h (* (:h sprites/frame-size) (:scale config))
        target-x (max (+ left 10) (min (- right cat-w 10) cat-x))
        dx (- target-x cat-x)
        target-y (- top cat-h)
        dy (- cat-y target-y)
        gravity (:gravity config)
        total-height (+ dy 15)
        vy (- (js/Math.sqrt (* 2 gravity total-height)))
        a (* 0.5 gravity)
        b vy
        c dy
        discriminant (- (* b b) (* 4 a c))
        t (when (>= discriminant 0)
            (/ (- (- b) (js/Math.sqrt discriminant)) (* 2 a)))
        vx (when (and t (pos? t))
             (/ dx t))]
    (when (and vx vy (< (js/Math.abs vx) 8))
      {:vx vx :vy vy})))

(defn check-surface-validity
  "Check if current surface is still valid."
  [current-surface]
  (when current-surface
    (let [el (:el current-surface)]
      (cond
        (not (.-isConnected el)) :removed
        :else
        (let [rect (.getBoundingClientRect el)
              vh js/window.innerHeight]
          (if (or (> (.-top rect) vh)
                  (< (.-bottom rect) 0))
            :scrolled-away
            :valid))))))

(defn floor-y []
  (- js/window.innerHeight 40 (* (:h sprites/frame-size) (:scale config))))

;; -- Effects (imperative shell — DOM side effects only, no atom writes) --

(defn perform-effect!
  "Execute a side effect. Returns {:env {...}} for env updates, or nil."
  [dispatch-fn effect]
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
                               (dispatch-fn [[:buddy/ax.enter-state :being-hit]]))))
        nil)

      :dom/fx.add-drag-handler
      (let [[container-node] args
            !drag-state (atom {:last-x nil :last-y nil :last-t nil :vx 0 :vy 0})
            move-handler (atom nil)
            up-handler (atom nil)
            down-handler
            (fn [e]
              (.preventDefault e)
              (.stopPropagation e)
              (let [x (.-clientX e)
                    y (.-clientY e)
                    now (js/Date.now)]
                (reset! !drag-state {:last-x x :last-y y :last-t now :vx 0 :vy 0})
                (dispatch-fn [[:buddy/ax.env-merge
                               :drag {:active? true :x x :y y :vx 0 :vy 0}]
                              [:buddy/ax.enter-state :dragging]])
                (reset! move-handler
                        (fn [me]
                          (let [mx (.-clientX me)
                                my (.-clientY me)
                                mt (js/Date.now)
                                {:keys [last-x last-y last-t]} @!drag-state
                                dt (max 1 (- mt (or last-t mt)))
                                vx (* (/ (- mx (or last-x mx)) dt) 8)
                                vy (* (/ (- my (or last-y my)) dt) 8)]
                            (reset! !drag-state {:last-x mx :last-y my :last-t mt
                                                 :vx vx :vy vy})
                            (dispatch-fn [[:buddy/ax.env-merge
                                           :drag {:active? true :x mx :y my
                                                  :vx vx :vy vy}]]))))
                (reset! up-handler
                        (fn [_ue]
                          (let [{:keys [vx vy]} @!drag-state]
                            (.removeEventListener js/document "mousemove" @move-handler)
                            (.removeEventListener js/document "mouseup" @up-handler)
                            (dispatch-fn [[:buddy/ax.env-merge
                                           :drag {:active? false :x nil :y nil :vx 0 :vy 0}]
                                          [:buddy/ax.drag-release vx vy]]))))
                (.addEventListener js/document "mousemove" @move-handler)
                (.addEventListener js/document "mouseup" @up-handler)))]
        (when container-node
          (.addEventListener container-node "mousedown" down-handler)
          {:env {:drag-handler down-handler}}))

      :dom/fx.remove-drag-handler
      (let [[container-node handler] args]
        (when (and container-node handler)
          (.removeEventListener container-node "mousedown" handler))
        nil)

      :dom/fx.add-mouse-tracker
      (let [!last-move (atom 0)
            handler (fn [e]
                      (let [now (js/Date.now)]
                        (when (> (- now @!last-move) 200)
                          (reset! !last-move now)
                          (dispatch-fn [[:buddy/ax.env-merge
                                         :mouse-x (.-clientX e)
                                         :mouse-y (.-clientY e)]]))))]
        (.addEventListener js/document "mousemove" handler)
        {:env {:mouse-handler handler}})

      :dom/fx.remove-mouse-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/document "mousemove" handler))
        nil)

      :dom/fx.add-click-tracker
      (let [handler (fn [e]
                      (dispatch-fn [[:buddy/ax.react-to-click (.-clientX e) (.-clientY e)]]))]
        (.addEventListener js/document "click" handler)
        {:env {:click-handler handler}})

      :dom/fx.remove-click-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/document "click" handler))
        nil)

      :dom/fx.add-scroll-tracker
      (let [!scan-timeout (atom nil)
            !prev-y (atom 0)
            handler (fn []
                      (let [curr-y (.-scrollY js/window)
                            dy (js/Math.abs (- curr-y @!prev-y))]
                        (reset! !prev-y curr-y)
                        (dispatch-fn
                         (cond-> [[:buddy/ax.env-merge :scroll-y curr-y]]
                           (> dy 500) (conj [:buddy/ax.enter-state :being-hit])))
                        (when-let [t @!scan-timeout]
                          (js/clearTimeout t))
                        (reset! !scan-timeout
                                (js/setTimeout
                                 #(dispatch-fn [[:buddy/ax.scan-surfaces]])
                                 500))))]
        (.addEventListener js/window "scroll" handler #js {:passive true})
        {:env {:scroll-handler handler}})

      :dom/fx.remove-scroll-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/window "scroll" handler))
        nil)

      :timer/fx.set-interval
      (let [[callback-action ms] args]
        (js/setInterval (fn [] (dispatch-fn [callback-action])) ms))

      :timer/fx.clear-interval
      (let [[timer-id] args]
        (when timer-id (js/clearInterval timer-id))
        nil)

      :log/fx.log
      (do (apply js/console.log args) nil)

      :dom/fx.scan-surfaces
      {:env {:surfaces (scan-surfaces-data)}}

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
  "Energy-weighted behavior selection from a random roll."
  [energy roll]
  (let [rest-bias (- 1.0 energy)
        active-bias energy
        weights {:sitting  (* 0.25 rest-bias)
                 :sleeping (if (< energy 0.2) (* 0.3 rest-bias) 0.0)
                 :meowing  0.12
                 :touching 0.08
                 :jumping  (* 0.10 active-bias)
                 :running  (* 0.20 active-bias)
                 :perching (* 0.15 active-bias)
                 :walking  (* 0.30 active-bias)}
        total (reduce + (vals weights))
        normalized (reduce-kv (fn [m k v] (assoc m k (/ v total))) {} weights)
        ordered [:sitting :sleeping :meowing :touching :jumping :running :perching :walking]
        cumulative (reductions + (map #(get normalized %) ordered))]
    (or (first (keep-indexed
                (fn [i cum]
                  (when (< roll cum) (nth ordered i)))
                cumulative))
        :walking)))

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
      (let [energy (or (:energy state) 0.8)
            scale (+ 0.3 (* 0.7 energy))
            [lo hi] (:walk-duration config)
            duration (rand-between (int (* lo scale)) (int (* hi scale)))
            new-facing (if (< (:roll uf-data) 0.5) :left :right)]
        {:uf/db (assoc base-db
                       :state-end (+ now duration)
                       :facing new-facing)
         :uf/fxs (into (anim-fxs el :walk)
                       (facing-fxs el new-facing))})

      :running
      (let [energy (or (:energy state) 0.8)
            scale (+ 0.3 (* 0.7 energy))
            [lo hi] (:walk-duration config)
            duration (rand-between (int (* lo scale)) (int (* hi scale)))
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

      :perching
      {:uf/db base-db
       :uf/fxs (anim-fxs el :walk)}

      :edge-contemplating
      (let [duration (rand-between 800 1500)]
        {:uf/db (assoc base-db :state-end (+ now duration))
         :uf/fxs (anim-fxs el :idle)})

      :dragging
      {:uf/db (assoc base-db :current-surface nil)
       :uf/fxs (anim-fxs el :being-hit)}

      :cursor-chasing
      {:uf/db base-db
       :uf/fxs (anim-fxs el :walk)}

      nil)))

(defn tick-walking
  "Pure walking tick logic, surface-bounded when on element."
  [state now]
  (let [{:keys [x y facing container el current-anim state-end current-surface]} state
        speed (if (= current-anim :run) (:run-speed config) (:walk-speed config))
        dx (if (= facing :left) (- speed) speed)
        new-x (+ x dx)
        cat-w (* (:w sprites/frame-size) (:scale config))
        [min-bound max-bound]
        (if current-surface
          (let [rect (.getBoundingClientRect (:el current-surface))]
            [(.-left rect) (- (.-right rect) cat-w)])
          [0 (- js/window.innerWidth cat-w)])
        edge-state (if current-surface :edge-contemplating :idle)
        move-result (cond
                      (< new-x min-bound)
                      {:uf/db (assoc state :x min-bound :facing :right)
                       :uf/fxs (into (position-fxs container min-bound y)
                                     (facing-fxs el :right))
                       :uf/dxs [[:buddy/ax.enter-state edge-state]]}

                      (> new-x max-bound)
                      {:uf/db (assoc state :x max-bound :facing :left)
                       :uf/fxs (into (position-fxs container max-bound y)
                                     (facing-fxs el :left))
                       :uf/dxs [[:buddy/ax.enter-state edge-state]]}

                      :else
                      {:uf/db (assoc state :x new-x)
                       :uf/fxs (position-fxs container new-x y)})]
    (if (and state-end (> now state-end))
      (update move-result :uf/dxs (fnil conj []) [:buddy/ax.enter-state :idle])
      move-result)))


(defn tick-jumping
  "Pure jumping/falling tick with gravity and surface detection."
  [state uf-data]
  (let [{:keys [x y vx vy container]} state
        gravity (:gravity config)
        terminal-vel (:terminal-vel config)
        new-vy (min (+ vy gravity) terminal-vel)
        new-x (+ x vx)
        new-y (+ y new-vy)
        max-x (- js/window.innerWidth (* (:w sprites/frame-size) (:scale config)))
        fy (floor-y)
        clamped-x (max 0 (min new-x max-x))
        surfaces (:surfaces/visible uf-data)
        landing-surface (when (and surfaces (> new-vy 0))
                          (find-landing-surface surfaces clamped-x new-y y))]
    (cond
      landing-surface
      (let [surface-y (- (:top landing-surface) (* (:h sprites/frame-size) (:scale config)))
            land-state (if (> new-vy 6) :stunned :idle)]
        {:uf/db (assoc state
                       :x clamped-x :y surface-y
                       :vx 0 :vy 0
                       :current-surface landing-surface)
         :uf/fxs (position-fxs container clamped-x surface-y)
         :uf/dxs [[:buddy/ax.enter-state land-state]]})

      (>= new-y fy)
      (let [land-state (if (> new-vy 6) :stunned :idle)]
        {:uf/db (assoc state
                       :x clamped-x :y fy
                       :vx 0 :vy 0
                       :current-surface nil)
         :uf/fxs (position-fxs container clamped-x fy)
         :uf/dxs [[:buddy/ax.enter-state land-state]]})

      :else
      {:uf/db (assoc state :x clamped-x :y new-y :vx vx :vy new-vy)
       :uf/fxs (position-fxs container clamped-x new-y)})))


(defn tick-idle
  "Tick handler for idle state — check timeout, cursor chase opportunity, mouse facing."
  [state uf-data]
  (let [now (:system/now uf-data)
        {:keys [state-end]} state
        mouse-x (:mouse/x uf-data)
        mouse-y (:mouse/y uf-data)
        last-mx (:last-mouse-x state)
        last-my (:last-mouse-y state)
        mouse-moved? (or (nil? last-mx)
                         (> (js/Math.abs (- (or mouse-x 0) last-mx)) 5)
                         (> (js/Math.abs (- (or mouse-y 0) last-my)) 5))
        still-since (if mouse-moved? now (or (:mouse-still-since state) now))
        mouse-still-time (- now still-since)
        state (assoc state
                     :last-mouse-x mouse-x
                     :last-mouse-y mouse-y
                     :mouse-still-since still-since)]
    (if (and state-end (> now state-end))
      {:uf/db state
       :uf/dxs [[:buddy/ax.enter-state (pick-next-behavior (or (:energy state) 0.8) (:roll uf-data))]]}
      (if (and mouse-x mouse-y (> mouse-still-time 2000))
        (let [cat-center-x (+ (:x state) (/ (* (:w sprites/frame-size) (:scale config)) 2))
              dx (- mouse-x cat-center-x)
              dy (- mouse-y (:y state))
              dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))]
          (if (and (< dist 500) (> dist 180) (< (rand) 0.005))
            {:uf/db state
             :uf/dxs [[:buddy/ax.enter-state :cursor-chasing]]}
            (or (mouse-facing-fxs state uf-data)
                {:uf/db state})))
        (or (mouse-facing-fxs state uf-data)
            {:uf/db state})))))

(defn tick-perching
  "Tick handler for perching — walk toward surface target and jump onto it."
  [state uf-data]
  (let [surfaces (:surfaces/visible uf-data)
        {:keys [x y facing container el]} state
        target (when surfaces (find-perch-target surfaces x y))]
    (if target
      (let [cat-w (* (:w sprites/frame-size) (:scale config))
            cat-center-x (+ x (/ cat-w 2))
            target-center-x (+ (:left target) (/ (:width target) 2))
            dx (- target-center-x cat-center-x)
            dist (js/Math.abs dx)
            walk-facing (if (pos? dx) :right :left)]
        (if (< dist 50)
          (let [jump-params (compute-jump-to-surface x y target)]
            (if jump-params
              {:uf/db (assoc state :vx (:vx jump-params) :vy (:vy jump-params)
                             :buddy-state :jumping)
               :uf/fxs (anim-fxs el :jump)}
              {:uf/dxs [[:buddy/ax.enter-state :idle]]}))
          (let [speed (:walk-speed config)
                step (if (= walk-facing :right) speed (- speed))
                new-x (+ x step)
                max-x (- js/window.innerWidth cat-w)
                clamped-x (max 0 (min new-x max-x))
                facing-update (when (not= walk-facing facing)
                                (facing-fxs el walk-facing))]
            {:uf/db (assoc state :x clamped-x :facing walk-facing)
             :uf/fxs (into (position-fxs container clamped-x y)
                           (or facing-update []))})))
      {:uf/dxs [[:buddy/ax.enter-state :idle]]})))

(defn tick-edge-contemplating
  "Tick handler for edge contemplation — decide to jump off or turn around."
  [state uf-data]
  (let [now (:system/now uf-data)
        {:keys [state-end facing el]} state]
    (when (and state-end (> now state-end))
      (let [jump-off? (< (rand) 0.4)]
        (if jump-off?
          (let [vx (if (= facing :left) -2 2)]
            {:uf/db (assoc state :vx vx :vy -2 :current-surface nil)
             :uf/dxs [[:buddy/ax.enter-state :jumping]]})
          (let [new-facing (if (= facing :left) :right :left)]
            {:uf/db (assoc state :facing new-facing)
             :uf/fxs (facing-fxs el new-facing)
             :uf/dxs [[:buddy/ax.enter-state :walking]]}))))))

(defn tick-dragging
  "Tick handler for dragging — follow drag position, handle break-free."
  [state uf-data]
  (let [now (:system/now uf-data)
        drag (:drag/data uf-data)
        {:keys [container]} state
        cat-h (* (:h sprites/frame-size) (:scale config))]
    (when (:active? drag)
      (let [dx (- (:x drag) (:x state))
            new-x (:x drag)
            new-y (- (:y drag) (/ cat-h 2))
            new-facing (cond (> dx 2) :right (< dx -2) :left :else (:facing state))
            held-time (- now (or (:state-timer state) now))
            break-free? (and (> held-time 2000) (< (rand) 0.05))]
        (if break-free?
          (let [vx (if (= new-facing :left) 4 -4)]
            {:uf/db (assoc state :vx vx :vy -6 :x new-x :y new-y :current-surface nil)
             :uf/dxs [[:buddy/ax.enter-state :jumping]]})
          {:uf/db (assoc state :x new-x :y new-y :facing new-facing)
           :uf/fxs (into (position-fxs container new-x new-y)
                         (when (not= new-facing (:facing state))
                           (facing-fxs (:el state) new-facing)))})))))

(defn tick-cursor-chasing
  "Tick handler for cursor chasing — walk toward mouse, stop if mouse moves or reached."
  [state uf-data]
  (let [mouse-x (:mouse/x uf-data)
        mouse-y (:mouse/y uf-data)
        {:keys [x y facing el container]} state
        cat-w (* (:w sprites/frame-size) (:scale config))
        cat-center-x (+ x (/ cat-w 2))]
    (if (nil? mouse-x)
      {:uf/dxs [[:buddy/ax.enter-state :idle]]}
      (let [dx (- mouse-x cat-center-x)
            dy (- mouse-y y)
            dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))
            last-mx (:last-mouse-x state)
            last-my (:last-mouse-y state)
            mouse-moved? (and last-mx
                              (or (> (js/Math.abs (- mouse-x last-mx)) 30)
                                  (> (js/Math.abs (- mouse-y last-my)) 30)))]
        (cond
          mouse-moved?
          {:uf/db (assoc state :last-mouse-x mouse-x :last-mouse-y mouse-y)
           :uf/dxs [[:buddy/ax.enter-state :idle]]}

          (<= dist 180)
          {:uf/db (assoc state :last-mouse-x mouse-x :last-mouse-y mouse-y)
           :uf/dxs [[:buddy/ax.enter-state :idle]]}

          :else
          (let [walk-facing (if (pos? dx) :right :left)
                speed (:walk-speed config)
                step (if (= walk-facing :right) speed (- speed))
                new-x (+ x step)
                max-x (- js/window.innerWidth cat-w)
                clamped-x (max 0 (min new-x max-x))
                facing-changed? (not= walk-facing facing)]
            {:uf/db (assoc state :x clamped-x :facing walk-facing
                           :last-mouse-x mouse-x :last-mouse-y mouse-y)
             :uf/fxs (into (position-fxs container clamped-x y)
                           (when facing-changed?
                             (facing-fxs el walk-facing)))}))))))

(defn init-action
  "Initialize buddy from DOM refs."
  [dom-refs]
  (let [init-x 100
        init-y (floor-y)]
    {:uf/db (merge dom-refs
                   {:facing :right
                    :buddy-state nil
                    :frame 0
                    :frame-count 0
                    :current-anim nil
                    :x init-x
                    :y init-y
                    :energy 0.8})
     :uf/fxs [[:dom/fx.inject-css (make-sprite-css)]
              [:dom/fx.set-transform (:container dom-refs) init-x init-y]
              [:dom/fx.add-click-handler (:el dom-refs)]
              [:dom/fx.add-drag-handler (:container dom-refs)]
              [:dom/fx.add-mouse-tracker]
              [:dom/fx.add-click-tracker]
              [:dom/fx.add-scroll-tracker]
              [:dom/fx.scan-surfaces]]
     :uf/dxs [[:buddy/ax.enter-state :idle]]}))

(defn react-to-click-action
  "React to a page click — flinch or face toward it."
  [state click-x click-y]
  (let [{:keys [x y buddy-state el]} state
        cat-center-x (+ x (/ (* (:w sprites/frame-size) (:scale config)) 2))
        dx (- click-x cat-center-x)
        dy (- click-y (+ y (/ (* (:h sprites/frame-size) (:scale config)) 2)))
        dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))
        ground-state? (contains? #{:idle :walking :running :sitting :meowing :touching :perching :edge-contemplating} buddy-state)]
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
             :uf/fxs (facing-fxs el face-dir)}))))))

(defn stop-action
  "Teardown — cancel RAF, remove handlers and DOM, reset env."
  [state uf-data]
  (let [{:keys [raf-id container]} state]
    {:uf/db nil
     :uf/fxs [[:dom/fx.cancel-raf raf-id]
              [:dom/fx.remove-drag-handler container (:env/drag-handler uf-data)]
              [:dom/fx.remove-mouse-tracker (:env/mouse-handler uf-data)]
              [:dom/fx.remove-click-tracker (:env/click-handler uf-data)]
              [:dom/fx.remove-scroll-tracker (:env/scroll-handler uf-data)]
              [:dom/fx.remove-element container]
              [:log/fx.log "Page buddy stopped."]]
     :uf/env {:mouse-x nil :mouse-y nil :scroll-y 0
              :drag {:active? false :x nil :y nil :vx 0 :vy 0}
              :surfaces nil :drag-handler nil :mouse-handler nil
              :click-handler nil :scroll-handler nil}}))

(defn handle-action
  "Pure action handler. Returns {:uf/db :uf/fxs :uf/dxs :uf/env} or nil."
  [state uf-data action]
  (let [[op & args] action
        now (:system/now uf-data)]
    (case op
      :buddy/ax.assoc
      {:uf/db (apply assoc state args)}

      :buddy/ax.init
      (let [[dom-refs] args] (init-action dom-refs))

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
                       :perching :walk
                       :edge-contemplating :idle
                       :dragging :being-hit
                       :cursor-chasing :walk
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
      (let [state (assoc state :energy (update-energy (or (:energy state) 0.8) (:buddy-state state)))
            {:keys [buddy-state state-end state-timer]} state
            surface-lost? (when-let [surface (:current-surface state)]
                            (not= (check-surface-validity surface) :valid))
            behavior-result
            (if surface-lost?
              {:uf/db (assoc state :current-surface nil)
               :uf/dxs [[:buddy/ax.enter-state :falling]]}
              (case buddy-state
                :idle (tick-idle state uf-data)
                :walking (tick-walking state now)
                :running (tick-walking state now)
                (:jumping :falling) (tick-jumping state uf-data)

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

                :perching (tick-perching state uf-data)
                :edge-contemplating (tick-edge-contemplating state uf-data)
                :dragging (tick-dragging state uf-data)
                :cursor-chasing (tick-cursor-chasing state uf-data)
                nil))]
        (if behavior-result
          (update behavior-result :uf/db #(or % state))
          {:uf/db state}))

      :buddy/ax.scan-surfaces
      {:uf/fxs [[:dom/fx.scan-surfaces]]}

      :buddy/ax.drag-release
      (let [[vx vy] args
            clamped-vx (max -15 (min 15 (or vx 0)))
            clamped-vy (max -15 (min 15 (or vy 0)))]
        {:uf/db (assoc state :vx clamped-vx :vy clamped-vy :current-surface nil)
         :uf/dxs [[:buddy/ax.enter-state :jumping]]})

      :buddy/ax.react-to-click
      (let [[click-x click-y] args]
        (react-to-click-action state click-x click-y))

      :buddy/ax.stop
      (stop-action state uf-data)

      :buddy/ax.env-merge
      {:uf/env (apply hash-map args)}

      (do (js/console.warn "Unhandled action:" (pr-str action))
          nil))))


;; -- Dispatch Loop (single state access point) --

(defn make-dispatch
  "Creates a dispatch function closed over the given state and env atoms.
   The returned function is the single access point for state and env."
  [!state !env]
  (fn dispatch! [actions]
    (let [env @!env]
      (loop [remaining (seq actions)
             state @!state
             all-fxs []
             all-dxs []
             all-env []]
        (if remaining
          (let [action (first remaining)
                result (handle-action state {:system/now (js/Date.now)
                                             :roll (rand)
                                             :mouse/x (:mouse-x env)
                                             :mouse/y (:mouse-y env)
                                             :scroll/y (:scroll-y env)
                                             :surfaces/visible (:surfaces env)
                                             :drag/data (:drag env)
                                             :env/drag-handler (:drag-handler env)
                                             :env/mouse-handler (:mouse-handler env)
                                             :env/click-handler (:click-handler env)
                                             :env/scroll-handler (:scroll-handler env)}
                                      action)
                new-state (if (and (map? result) (contains? result :uf/db))
                            (:uf/db result)
                            state)
                fxs (when (map? result) (:uf/fxs result))
                dxs (when (map? result) (:uf/dxs result))
                env-upd (when (map? result) (:uf/env result))]
            (recur (next remaining)
                   new-state
                   (into all-fxs fxs)
                   (into all-dxs dxs)
                   (cond-> all-env env-upd (conj env-upd))))
          (do
            (reset! !state state)
            (when (seq all-env)
              (swap! !env #(reduce merge % all-env)))
            (doseq [fx all-fxs]
              (when fx
                (when-let [env-upd (:env (perform-effect! dispatch! fx))]
                  (swap! !env merge env-upd))))
            (when (seq all-dxs)
              (dispatch! all-dxs))))))))

(def dispatch!
  "Global dispatch function for REPL convenience. Created from make-dispatch."
  (make-dispatch !state !env))

;; -- Lifecycle (entry points dispatch actions only) --

(defn make-raf-loop
  "Creates a requestAnimationFrame callback that drives sprite and state ticks."
  [dispatch-fn !state]
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
          (dispatch-fn actions))
        (when @!state
          (dispatch-fn [[:buddy/ax.assoc :raf-id (js/requestAnimationFrame raf-cb)]]))))))

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
    (let [raf-cb (make-raf-loop dispatch! !state)
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
  @!env
  (select-keys @!env [:mouse-x :mouse-y])
  :rcf)
