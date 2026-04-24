(ns page-buddy)

(require '[page-buddy-sprites])

;; -- Configuration --

(def config
  {:cfg/scale 3
   :cfg/fps 8
   :cfg/walk-speed 2
   :cfg/run-speed 4
   :cfg/idle-duration [3000 8000]
   :cfg/walk-duration [2000 6000]
   :cfg/sit-chance 0.3
   :cfg/sleep-delay 5000
   :cfg/meow-chance 0.15
   :cfg/touch-chance 0.1
   :cfg/run-chance 0.3
   :cfg/jump-chance 0.08
   :cfg/gravity 0.5
   :cfg/terminal-vel 12
   :cfg/jump-vy -8})

;; -- Helpers --

(def energy-rates
  {:bs/walking  -0.002
   :bs/running  -0.004
   :bs/jumping  -0.003
   :bs/falling  -0.001
   :bs/sitting   0.003
   :bs/sleeping  0.005
   :bs/idle      0.001
   :bs/meowing   0.0
   :bs/touching  0.0
   :bs/being-hit -0.005
   :bs/stunned   0.0
   :bs/landing   0.0
   :bs/perching -0.002
   :bs/edge-contemplating 0.001
   :bs/dragging 0.0
   :bs/cursor-chasing -0.002})

(defonce !state (atom nil))

(defonce !env (atom {:mouse/x nil :mouse/y nil :scroll/y 0}))

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
        cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))]
    (->> (js/document.querySelectorAll
          "nav, header, aside, section, article, div, img, table, footer, main")
         (keep (fn [el]
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
                       {:dom/el el
                        :geom/top top
                        :geom/left left
                        :geom/right right
                        :geom/bottom bottom
                        :geom/width w
                        :geom/height h})))))
         vec)))

(defn find-landing-surface
  "Check if cat's feet crossed through a surface top edge during fall."
  [surfaces cat-x cat-y cat-prev-y]
  (let [cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))
        cat-h (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config))
        cat-right (+ cat-x cat-w)
        cat-bottom (+ cat-y cat-h)
        cat-prev-bottom (+ cat-prev-y cat-h)]
    (when (> cat-y cat-prev-y)
      (first
       (filter (fn [{:geom/keys [top left right width]}]
                 (and (<= cat-prev-bottom top)
                      (>= cat-bottom top)
                      (< cat-x right)
                      (> cat-right left)
                      (> width cat-w)))
               surfaces)))))

(defn find-perch-target
  "Find best surface to jump onto from current position."
  [surfaces cat-x cat-y]
  (let [cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))
        cat-center-x (+ cat-x (/ cat-w 2))
        max-jump-height 200
        max-horiz-dist 300]
    (->> surfaces
         (filter (fn [{:geom/keys [top left width]}]
                   (let [surface-center-x (+ left (/ width 2))
                         horiz-dist (js/Math.abs (- surface-center-x cat-center-x))
                         height-diff (- cat-y top)]
                     (and (> height-diff 20)
                          (< height-diff max-jump-height)
                          (< horiz-dist max-horiz-dist)
                          (> width cat-w)))))
         (sort-by (fn [{:geom/keys [top left width]}]
                    (let [surface-center-x (+ left (/ width 2))
                          horiz-dist (js/Math.abs (- surface-center-x cat-center-x))
                          height-diff (- cat-y top)]
                      (+ horiz-dist (* height-diff 0.5)))))
         first)))

(defn compute-jump-to-surface
  "Compute vx/vy to jump from (cat-x, cat-y) to land on surface top edge."
  [cat-x cat-y surface]
  (let [{:geom/keys [top left right]} surface
        cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))
        cat-h (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config))
        target-x (max (+ left 10) (min (- right cat-w 10) cat-x))
        dx (- target-x cat-x)
        target-y (- top cat-h)
        dy (- cat-y target-y)
        gravity (:cfg/gravity config)
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
      {:vel/x vx :vel/y vy})))

(defn check-surface-validity
  "Check if current surface is still valid."
  [current-surface]
  (when current-surface
    (let [el (:dom/el current-surface)]
      (if (not (.-isConnected el))
        :surface/removed
        (let [rect (.getBoundingClientRect el)
              vh js/window.innerHeight]
          (if (or (> (.-top rect) vh)
                  (< (.-bottom rect) 0))
            :surface/scrolled-away
            :surface/valid))))))

(defn floor-y []
  (- js/window.innerHeight 40 (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config))))

;; -- Effects (imperative shell — DOM side effects only, no atom writes) --

(defn perform-effect!
  "Execute a side effect. Returns {:uf/env {...}} for env updates, or nil."
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
        {:dom/container container :dom/el el})

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
                               (dispatch-fn [[:buddy/ax.enter-state :bs/being-hit]]))))
        nil)

      :dom/fx.add-drag-handler
      (let [[container-node] args
            !drag-state (atom {:drag/last-x nil :drag/last-y nil :drag/last-t nil :vel/x 0 :vel/y 0})
            move-handler (atom nil)
            up-handler (atom nil)
            down-handler
            (fn [e]
              (.preventDefault e)
              (.stopPropagation e)
              (let [x (.-clientX e)
                    y (.-clientY e)
                    now (js/Date.now)]
                (reset! !drag-state {:drag/last-x x :drag/last-y y :drag/last-t now :vel/x 0 :vel/y 0})
                (dispatch-fn [[:buddy/ax.env-merge
                               :drag/data {:drag/active? true :pos/x x :pos/y y :vel/x 0 :vel/y 0}]
                              [:buddy/ax.enter-state :bs/dragging]])
                (reset! move-handler
                        (fn [me]
                          (let [mx (.-clientX me)
                                my (.-clientY me)
                                mt (js/Date.now)
                                drag-last-x (:drag/last-x @!drag-state)
                                drag-last-y (:drag/last-y @!drag-state)
                                drag-last-t (:drag/last-t @!drag-state)
                                dt (max 1 (- mt (or drag-last-t mt)))
                                vx (* (/ (- mx (or drag-last-x mx)) dt) 8)
                                vy (* (/ (- my (or drag-last-y my)) dt) 8)]
                            (reset! !drag-state {:drag/last-x mx :drag/last-y my :drag/last-t mt
                                                 :vel/x vx :vel/y vy})
                            (dispatch-fn [[:buddy/ax.env-merge
                                           :drag/data {:drag/active? true :pos/x mx :pos/y my
                                                       :vel/x vx :vel/y vy}]]))))
                (reset! up-handler
                        (fn [_ue]
                          (let [vx (:vel/x @!drag-state)
                                vy (:vel/y @!drag-state)]
                            (.removeEventListener js/document "mousemove" @move-handler)
                            (.removeEventListener js/document "mouseup" @up-handler)
                            (dispatch-fn [[:buddy/ax.env-merge
                                           :drag/data {:drag/active? false :pos/x nil :pos/y nil :vel/x 0 :vel/y 0}]
                                          [:buddy/ax.drag-release vx vy]]))))
                (.addEventListener js/document "mousemove" @move-handler)
                (.addEventListener js/document "mouseup" @up-handler)))]
        (when container-node
          (.addEventListener container-node "mousedown" down-handler)
          {:uf/env {:env/drag-handler down-handler}}))

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
                                         :mouse/x (.-clientX e)
                                         :mouse/y (.-clientY e)]]))))]
        (.addEventListener js/document "mousemove" handler)
        {:uf/env {:env/mouse-handler handler}})

      :dom/fx.remove-mouse-tracker
      (let [[handler] args]
        (when handler
          (.removeEventListener js/document "mousemove" handler))
        nil)

      :dom/fx.add-click-tracker
      (let [handler (fn [e]
                      (dispatch-fn [[:buddy/ax.react-to-click (.-clientX e) (.-clientY e)]]))]
        (.addEventListener js/document "click" handler)
        {:uf/env {:env/click-handler handler}})

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
                         (cond-> [[:buddy/ax.env-merge :scroll/y curr-y]]
                           (> dy 500) (conj [:buddy/ax.enter-state :bs/being-hit])))
                        (when-let [t @!scan-timeout]
                          (js/clearTimeout t))
                        (reset! !scan-timeout
                                (js/setTimeout
                                 #(dispatch-fn [[:buddy/ax.scan-surfaces]])
                                 500))))]
        (.addEventListener js/window "scroll" handler #js {"passive" true})
        {:uf/env {:env/scroll-handler handler}})

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
      {:uf/env {:surfaces/visible (scan-surfaces-data)}}

      (do (js/console.warn "Unhandled effect:" (pr-str effect)) nil))))


;; -- Pure helpers for actions --

(defn make-sprite-css []
  (let [{:sprite/keys [w h]} page-buddy-sprites/frame-size
        s (:cfg/scale config)]
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
  (let [{:anim/keys [frames data]} (get page-buddy-sprites/animations anim-key)
        {:sprite/keys [w h]} page-buddy-sprites/frame-size
        s (:cfg/scale config)
        sheet-w (* w frames s)]
    [[:dom/fx.set-style el "backgroundImage" (str "url(" data ")")]
     [:dom/fx.set-style el "backgroundSize" (str sheet-w "px " (* h s) "px")]
     [:dom/fx.set-style el "backgroundPosition" "0px 0"]]))

(defn facing-fxs
  "Effect vectors to set facing direction on an element."
  [el direction]
  [[:dom/fx.set-class el "facing-left" (= direction :facing/left)]])

(defn position-fxs
  "Effect vectors to update container position via translate3d."
  [container x y]
  [[:dom/fx.set-transform container x y]])

(defn pick-next-behavior
  "Energy-weighted behavior selection from a random roll."
  [energy roll]
  (let [rest-bias (- 1.0 energy)
        active-bias energy
        weights {:bs/sitting  (* 0.25 rest-bias)
                 :bs/sleeping (if (< energy 0.2) (* 0.3 rest-bias) 0.0)
                 :bs/meowing  0.12
                 :bs/touching 0.08
                 :bs/jumping  (* 0.10 active-bias)
                 :bs/running  (* 0.20 active-bias)
                 :bs/perching (* 0.15 active-bias)
                 :bs/walking  (* 0.30 active-bias)}
        total (reduce + (vals weights))
        normalized (reduce-kv (fn [m k v] (assoc m k (/ v total))) {} weights)
        ordered [:bs/sitting :bs/sleeping :bs/meowing :bs/touching :bs/jumping :bs/running :bs/perching :bs/walking]
        cumulative (reductions + (map #(get normalized %) ordered))]
    (or (first (keep-indexed
                (fn [i cum]
                  (when (< roll cum) (nth ordered i)))
                cumulative))
        :bs/walking)))

;; -- Actions (pure: state + uf-data + action → result) --

(defn mouse-facing-fxs
  "Returns facing update map if mouse position suggests different facing, nil otherwise."
  [state uf-data]
  (let [mouse-x (:mouse/x uf-data)]
    (when (and mouse-x (:pos/x state) (:dom/el state))
      (let [cat-center-x (+ (:pos/x state) (/ (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config)) 2))
            should-face (if (< mouse-x cat-center-x) :facing/left :facing/right)]
        (when (not= should-face (:buddy/facing state))
          {:uf/db (assoc state :buddy/facing should-face)
           :uf/fxs (facing-fxs (:dom/el state) should-face)})))))

(defn enter-state-action
  "Pure state transition. Returns {:uf/db :uf/fxs}."
  [state uf-data new-bstate]
  (let [el (:dom/el state)
        now (:system/now uf-data)
        base-db (assoc state :buddy/state new-bstate :buddy/state-timer now)]
    (case new-bstate
      :bs/idle
      (let [[lo hi] (:cfg/idle-duration config)
            duration (rand-between lo hi)]
        {:uf/db (assoc base-db :buddy/state-end (+ now duration))
         :uf/fxs (anim-fxs el :anim/idle)})

      :bs/walking
      (let [energy (or (:buddy/energy state) 0.8)
            scale (+ 0.3 (* 0.7 energy))
            [lo hi] (:cfg/walk-duration config)
            duration (rand-between (int (* lo scale)) (int (* hi scale)))
            new-facing (if (< (:rng/roll uf-data) 0.5) :facing/left :facing/right)]
        {:uf/db (assoc base-db
                       :buddy/state-end (+ now duration)
                       :buddy/facing new-facing)
         :uf/fxs (into (anim-fxs el :anim/walk)
                       (facing-fxs el new-facing))})

      :bs/running
      (let [energy (or (:buddy/energy state) 0.8)
            scale (+ 0.3 (* 0.7 energy))
            [lo hi] (:cfg/walk-duration config)
            duration (rand-between (int (* lo scale)) (int (* hi scale)))
            new-facing (if (< (:rng/roll uf-data) 0.5) :facing/left :facing/right)]
        {:uf/db (assoc base-db
                       :buddy/state-end (+ now duration)
                       :buddy/facing new-facing)
         :uf/fxs (into (anim-fxs el :anim/run)
                       (facing-fxs el new-facing))})

      :bs/jumping
      (let [vx (if (= (:buddy/facing state) :facing/right)
                 (* 0.5 (:cfg/walk-speed config))
                 (* -0.5 (:cfg/walk-speed config)))
            vy (:cfg/jump-vy config)]
        {:uf/db (assoc base-db :vel/x vx :vel/y vy)
         :uf/fxs (anim-fxs el :anim/jump)})

      :bs/falling
      {:uf/db (assoc base-db :vel/x 0 :vel/y 0)
       :uf/fxs (anim-fxs el :anim/jump)}

      :bs/landing
      {:uf/db (assoc base-db :buddy/state-end (+ now 500))
       :uf/fxs (anim-fxs el :anim/stunned)}

      :bs/being-hit
      {:uf/db (assoc base-db :buddy/state-end (+ now 400))
       :uf/fxs (anim-fxs el :anim/being-hit)}

      :bs/stunned
      {:uf/db (assoc base-db :buddy/state-end (+ now 800))
       :uf/fxs (anim-fxs el :anim/stunned)}

      :bs/sitting
      {:uf/db base-db
       :uf/fxs (anim-fxs el :anim/sit)}

      :bs/sleeping
      {:uf/db (assoc base-db :buddy/state-end (+ now (:cfg/sleep-delay config)))
       :uf/fxs (anim-fxs el :anim/sleep)}

      :bs/meowing
      {:uf/db (assoc base-db :buddy/state-end (+ now 800))
       :uf/fxs (anim-fxs el :anim/meow)}

      :bs/touching
      {:uf/db (assoc base-db :buddy/state-end (+ now 800))
       :uf/fxs (anim-fxs el :anim/touch)}

      :bs/perching
      {:uf/db base-db
       :uf/fxs (anim-fxs el :anim/walk)}

      :bs/edge-contemplating
      (let [duration (rand-between 800 1500)]
        {:uf/db (assoc base-db :buddy/state-end (+ now duration))
         :uf/fxs (anim-fxs el :anim/idle)})

      :bs/dragging
      {:uf/db (assoc base-db :buddy/current-surface nil)
       :uf/fxs (anim-fxs el :anim/being-hit)}

      :bs/cursor-chasing
      {:uf/db base-db
       :uf/fxs (anim-fxs el :anim/walk)}

      nil)))

(defn tick-walking
  "Pure walking tick logic, surface-bounded when on element."
  [state now]
  (let [{:pos/keys [x y]
         :dom/keys [container el]
         :buddy/keys [facing current-anim state-end current-surface]} state
        speed (if (= current-anim :anim/run) (:cfg/run-speed config) (:cfg/walk-speed config))
        dx (if (= facing :facing/left) (- speed) speed)
        new-x (+ x dx)
        cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))
        [min-bound max-bound]
        (if current-surface
          (let [rect (.getBoundingClientRect (:dom/el current-surface))]
            [(.-left rect) (- (.-right rect) cat-w)])
          [0 (- js/window.innerWidth cat-w)])
        edge-state (if current-surface :bs/edge-contemplating :bs/idle)
        move-result (if (< new-x min-bound)
                      {:uf/db (assoc state :pos/x min-bound :buddy/facing :facing/right)
                       :uf/fxs (into (position-fxs container min-bound y)
                                     (facing-fxs el :facing/right))
                       :uf/dxs [[:buddy/ax.enter-state edge-state]]}
                      (if (> new-x max-bound)
                        {:uf/db (assoc state :pos/x max-bound :buddy/facing :facing/left)
                         :uf/fxs (into (position-fxs container max-bound y)
                                       (facing-fxs el :facing/left))
                         :uf/dxs [[:buddy/ax.enter-state edge-state]]}
                        {:uf/db (assoc state :pos/x new-x)
                         :uf/fxs (position-fxs container new-x y)}))]
    (if (and state-end (> now state-end))
      (update move-result :uf/dxs (fnil conj []) [:buddy/ax.enter-state :bs/idle])
      move-result)))


(defn tick-jumping
  "Pure jumping/falling tick with gravity and surface detection."
  [state uf-data]
  (let [x (:pos/x state)
        y (:pos/y state)
        vx (:vel/x state)
        vy (:vel/y state)
        container (:dom/container state)
        gravity (:cfg/gravity config)
        terminal-vel (:cfg/terminal-vel config)
        new-vy (min (+ vy gravity) terminal-vel)
        new-x (+ x vx)
        new-y (+ y new-vy)
        max-x (- js/window.innerWidth (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config)))
        fy (floor-y)
        clamped-x (max 0 (min new-x max-x))
        surfaces (:surfaces/visible uf-data)
        landing-surface (when (and surfaces (> new-vy 0))
                          (find-landing-surface surfaces clamped-x new-y y))]
    (if landing-surface
      (let [surface-y (- (:geom/top landing-surface) (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config)))
            land-state (if (> new-vy 6) :bs/stunned :bs/idle)]
        {:uf/db (assoc state
                       :pos/x clamped-x :pos/y surface-y
                       :vel/x 0 :vel/y 0
                       :buddy/current-surface landing-surface)
         :uf/fxs (position-fxs container clamped-x surface-y)
         :uf/dxs [[:buddy/ax.enter-state land-state]]})
      (if (>= new-y fy)
        (let [land-state (if (> new-vy 6) :bs/stunned :bs/idle)]
          {:uf/db (assoc state
                         :pos/x clamped-x :pos/y fy
                         :vel/x 0 :vel/y 0
                         :buddy/current-surface nil)
           :uf/fxs (position-fxs container clamped-x fy)
           :uf/dxs [[:buddy/ax.enter-state land-state]]})
        {:uf/db (assoc state :pos/x clamped-x :pos/y new-y :vel/x vx :vel/y new-vy)
         :uf/fxs (position-fxs container clamped-x new-y)}))))


(defn tick-idle
  "Tick handler for idle state — check timeout, cursor chase opportunity, mouse facing."
  [state uf-data]
  (let [now (:system/now uf-data)
        state-end (:buddy/state-end state)
        mouse-x (:mouse/x uf-data)
        mouse-y (:mouse/y uf-data)
        last-mx (:buddy/last-mouse-x state)
        last-my (:buddy/last-mouse-y state)
        mouse-moved? (or (nil? last-mx)
                         (> (js/Math.abs (- (or mouse-x 0) last-mx)) 5)
                         (> (js/Math.abs (- (or mouse-y 0) last-my)) 5))
        still-since (if mouse-moved? now (or (:buddy/mouse-still-since state) now))
        mouse-still-time (- now still-since)
        state (assoc state
                     :buddy/last-mouse-x mouse-x
                     :buddy/last-mouse-y mouse-y
                     :buddy/mouse-still-since still-since)]
    (if (and state-end (> now state-end))
      {:uf/db state
       :uf/dxs [[:buddy/ax.enter-state (pick-next-behavior (or (:buddy/energy state) 0.8) (:rng/roll uf-data))]]}
      (if (and mouse-x mouse-y (> mouse-still-time 2000))
        (let [cat-center-x (+ (:pos/x state) (/ (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config)) 2))
              dx (- mouse-x cat-center-x)
              dy (- mouse-y (:pos/y state))
              dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))]
          (if (and (< dist 500) (> dist 180) (< (rand) 0.005))
            {:uf/db state
             :uf/dxs [[:buddy/ax.enter-state :bs/cursor-chasing]]}
            (or (mouse-facing-fxs state uf-data)
                {:uf/db state})))
        (or (mouse-facing-fxs state uf-data)
            {:uf/db state})))))

(defn tick-perching
  "Tick handler for perching — walk toward surface target and jump onto it."
  [state uf-data]
  (let [surfaces (:surfaces/visible uf-data)
        {:pos/keys [x y]
         :dom/keys [container el]
         :buddy/keys [facing]} state
        target (when surfaces (find-perch-target surfaces x y))]
    (if target
      (let [cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))
            cat-center-x (+ x (/ cat-w 2))
            target-center-x (+ (:geom/left target) (/ (:geom/width target) 2))
            dx (- target-center-x cat-center-x)
            dist (js/Math.abs dx)
            walk-facing (if (pos? dx) :facing/right :facing/left)]
        (if (< dist 50)
          (let [jump-params (compute-jump-to-surface x y target)]
            (if jump-params
              {:uf/db (assoc state :vel/x (:vel/x jump-params) :vel/y (:vel/y jump-params)
                             :buddy/state :bs/jumping)
               :uf/fxs (anim-fxs el :anim/jump)}
              {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]}))
          (let [speed (:cfg/walk-speed config)
                step (if (= walk-facing :facing/right) speed (- speed))
                new-x (+ x step)
                max-x (- js/window.innerWidth cat-w)
                clamped-x (max 0 (min new-x max-x))
                facing-update (when (not= walk-facing facing)
                                (facing-fxs el walk-facing))]
            {:uf/db (assoc state :pos/x clamped-x :buddy/facing walk-facing)
             :uf/fxs (into (position-fxs container clamped-x y)
                           (or facing-update []))})))
      {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]})))

(defn tick-edge-contemplating
  "Tick handler for edge contemplation — decide to jump off or turn around."
  [state uf-data]
  (let [now (:system/now uf-data)
        {:buddy/keys [state-end facing]
         :dom/keys [el]} state]
    (when (and state-end (> now state-end))
      (let [jump-off? (< (rand) 0.4)]
        (if jump-off?
          (let [vx (if (= facing :facing/left) -2 2)]
            {:uf/db (assoc state :vel/x vx :vel/y -2 :buddy/current-surface nil)
             :uf/dxs [[:buddy/ax.enter-state :bs/jumping]]})
          (let [new-facing (if (= facing :facing/left) :facing/right :facing/left)]
            {:uf/db (assoc state :buddy/facing new-facing)
             :uf/fxs (facing-fxs el new-facing)
             :uf/dxs [[:buddy/ax.enter-state :bs/walking]]}))))))

(defn tick-dragging
  "Tick handler for dragging — follow drag position, handle break-free."
  [state uf-data]
  (let [now (:system/now uf-data)
        drag (:drag/data uf-data)
        container (:dom/container state)
        cat-h (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config))]
    (when (:drag/active? drag)
      (let [dx (- (:pos/x drag) (:pos/x state))
            new-x (:pos/x drag)
            new-y (- (:pos/y drag) (/ cat-h 2))
            new-facing (if (> dx 2)
                         :facing/right
                         (if (< dx -2)
                           :facing/left
                           (:buddy/facing state)))
            held-time (- now (or (:buddy/state-timer state) now))
            break-free? (and (> held-time 2000) (< (rand) 0.05))]
        (if break-free?
          (let [vx (if (= new-facing :facing/left) 4 -4)]
            {:uf/db (assoc state :vel/x vx :vel/y -6 :pos/x new-x :pos/y new-y :buddy/current-surface nil)
             :uf/dxs [[:buddy/ax.enter-state :bs/jumping]]})
          {:uf/db (assoc state :pos/x new-x :pos/y new-y :buddy/facing new-facing)
           :uf/fxs (into (position-fxs container new-x new-y)
                         (when (not= new-facing (:buddy/facing state))
                           (facing-fxs (:dom/el state) new-facing)))})))))

(defn tick-cursor-chasing
  "Tick handler for cursor chasing — walk toward mouse, stop if mouse moves or reached."
  [state uf-data]
  (let [mouse-x (:mouse/x uf-data)
        mouse-y (:mouse/y uf-data)
        {:pos/keys [x y]
         :dom/keys [container el]
         :buddy/keys [facing]} state
        cat-w (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config))
        cat-center-x (+ x (/ cat-w 2))]
    (if (nil? mouse-x)
      {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]}
      (let [dx (- mouse-x cat-center-x)
            dy (- mouse-y y)
            dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))
            last-mx (:buddy/last-mouse-x state)
            last-my (:buddy/last-mouse-y state)
            mouse-moved? (and last-mx
                              (or (> (js/Math.abs (- mouse-x last-mx)) 30)
                                  (> (js/Math.abs (- mouse-y last-my)) 30)))]
        (if mouse-moved?
          {:uf/db (assoc state :buddy/last-mouse-x mouse-x :buddy/last-mouse-y mouse-y)
           :uf/dxs [[:buddy/ax.enter-state :bs/idle]]}
          (if (<= dist 180)
            {:uf/db (assoc state :buddy/last-mouse-x mouse-x :buddy/last-mouse-y mouse-y)
             :uf/dxs [[:buddy/ax.enter-state :bs/idle]]}
            (let [walk-facing (if (pos? dx) :facing/right :facing/left)
                  speed (:cfg/walk-speed config)
                  step (if (= walk-facing :facing/right) speed (- speed))
                  new-x (+ x step)
                  max-x (- js/window.innerWidth cat-w)
                  clamped-x (max 0 (min new-x max-x))
                  facing-changed? (not= walk-facing facing)]
              {:uf/db (assoc state :pos/x clamped-x :buddy/facing walk-facing
                             :buddy/last-mouse-x mouse-x :buddy/last-mouse-y mouse-y)
               :uf/fxs (into (position-fxs container clamped-x y)
                             (when facing-changed?
                               (facing-fxs el walk-facing)))})))))))

(defn init-action
  "Initialize buddy from DOM refs."
  [dom-refs]
  (let [init-x 100
        init-y (floor-y)]
    {:uf/db (merge dom-refs
                   {:buddy/facing :facing/right
                    :buddy/state nil
                    :buddy/frame 0
                    :buddy/frame-count 0
                    :buddy/current-anim nil
                    :pos/x init-x
                    :pos/y init-y
                    :buddy/energy 0.8})
     :uf/fxs [[:dom/fx.inject-css (make-sprite-css)]
              [:dom/fx.set-transform (:dom/container dom-refs) init-x init-y]
              [:dom/fx.add-click-handler (:dom/el dom-refs)]
              [:dom/fx.add-drag-handler (:dom/container dom-refs)]
              [:dom/fx.add-mouse-tracker]
              [:dom/fx.add-click-tracker]
              [:dom/fx.add-scroll-tracker]
              [:dom/fx.scan-surfaces]]
     :uf/dxs [[:buddy/ax.enter-state :bs/idle]]}))

(defn react-to-click-action
  "React to a page click — flinch or face toward it."
  [state click-x click-y]
  (let [{:pos/keys [x y]
         :dom/keys [el]} state
        buddy-state (:buddy/state state)
        cat-center-x (+ x (/ (* (:sprite/w page-buddy-sprites/frame-size) (:cfg/scale config)) 2))
        dx (- click-x cat-center-x)
        dy (- click-y (+ y (/ (* (:sprite/h page-buddy-sprites/frame-size) (:cfg/scale config)) 2)))
        dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))
        ground-state? (contains? #{:bs/idle :bs/walking :bs/running :bs/sitting :bs/meowing :bs/touching :bs/perching :bs/edge-contemplating} buddy-state)]
    (when ground-state?
      (cond
        (and (< dist 200) (< (rand) 0.3))
        (let [run-facing (if (pos? dx) :facing/left :facing/right)]
          {:uf/db (assoc state :buddy/facing run-facing)
           :uf/fxs (facing-fxs el run-facing)
           :uf/dxs [[:buddy/ax.enter-state :bs/being-hit]]})

        (< dist 500)
        (let [face-dir (if (pos? dx) :facing/right :facing/left)]
          (when (not= face-dir (:buddy/facing state))
            {:uf/db (assoc state :buddy/facing face-dir)
             :uf/fxs (facing-fxs el face-dir)}))))))

(defn stop-action
  "Teardown — cancel RAF, remove handlers and DOM, reset env."
  [state uf-data]
  (let [{:buddy/keys [raf-id]
         :dom/keys [container]} state]
    {:uf/db nil
     :uf/fxs [[:dom/fx.cancel-raf raf-id]
              [:dom/fx.remove-drag-handler container (:env/drag-handler uf-data)]
              [:dom/fx.remove-mouse-tracker (:env/mouse-handler uf-data)]
              [:dom/fx.remove-click-tracker (:env/click-handler uf-data)]
              [:dom/fx.remove-scroll-tracker (:env/scroll-handler uf-data)]
              [:dom/fx.remove-element container]
              [:log/fx.log "Page buddy stopped."]]
     :uf/env {:mouse/x nil :mouse/y nil :scroll/y 0
              :drag/data {:drag/active? false :pos/x nil :pos/y nil :vel/x 0 :vel/y 0}
              :surfaces/visible nil :env/drag-handler nil :env/mouse-handler nil
              :env/click-handler nil :env/scroll-handler nil}}))

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
                       :bs/idle :anim/idle
                       :bs/walking :anim/walk
                       :bs/running :anim/run
                       :bs/jumping :anim/jump
                       :bs/falling :anim/jump
                       :bs/landing :anim/stunned
                       :bs/being-hit :anim/being-hit
                       :bs/stunned :anim/stunned
                       :bs/sitting :anim/sit
                       :bs/sleeping :anim/sleep
                       :bs/meowing :anim/meow
                       :bs/touching :anim/touch
                       :bs/perching :anim/walk
                       :bs/edge-contemplating :anim/idle
                       :bs/dragging :anim/being-hit
                       :bs/cursor-chasing :anim/walk
                       nil)]
        (when result
          (let [frames (get-in page-buddy-sprites/animations [anim-key :anim/frames] 0)]
            (-> result
                (assoc-in [:uf/db :buddy/current-anim] anim-key)
                (assoc-in [:uf/db :buddy/frame] 0)
                (assoc-in [:uf/db :buddy/frame-count] frames)))))

      :buddy/ax.advance-frame
      (let [{:dom/keys [el]
             :buddy/keys [frame frame-count]} state]
        (when (and el (pos? frame-count))
          (let [{:sprite/keys [w]} page-buddy-sprites/frame-size
                s (:cfg/scale config)
                next-frame (mod (inc frame) frame-count)
                offset (* next-frame w s)]
            {:uf/db (assoc state :buddy/frame next-frame)
             :uf/fxs [[:dom/fx.set-style el "backgroundPosition"
                       (str "-" offset "px 0")]]})))

      :buddy/ax.tick
      (let [state (assoc state :buddy/energy (update-energy (or (:buddy/energy state) 0.8) (:buddy/state state)))
            buddy-state (:buddy/state state)
            state-end (:buddy/state-end state)
            state-timer (:buddy/state-timer state)
            surface-lost? (when-let [surface (:buddy/current-surface state)]
                            (not= (check-surface-validity surface) :surface/valid))
            behavior-result
            (if surface-lost?
              {:uf/db (assoc state :buddy/current-surface nil)
               :uf/dxs [[:buddy/ax.enter-state :bs/falling]]}
              (case buddy-state
                :bs/idle (tick-idle state uf-data)
                :bs/walking (tick-walking state now)
                :bs/running (tick-walking state now)
                (:bs/jumping :bs/falling) (tick-jumping state uf-data)

                :bs/landing
                (when (and state-end (> now state-end))
                  {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]})

                :bs/being-hit
                (when (and state-end (> now state-end))
                  {:uf/dxs [[:buddy/ax.enter-state :bs/stunned]]})

                :bs/stunned
                (when (and state-end (> now state-end))
                  {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]})

                :bs/sitting
                (let [sit-frames (get-in page-buddy-sprites/animations [:anim/sit :anim/frames])
                      elapsed (- now state-timer)]
                  (when (> elapsed (* sit-frames (/ 1000 (:cfg/fps config))))
                    {:uf/dxs [[:buddy/ax.enter-state :bs/sleeping]]}))

                :bs/sleeping
                (when (and state-end (> now state-end))
                  {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]})

                :bs/meowing
                (when (and state-end (> now state-end))
                  {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]})

                :bs/touching
                (when (and state-end (> now state-end))
                  {:uf/dxs [[:buddy/ax.enter-state :bs/idle]]})

                :bs/perching (tick-perching state uf-data)
                :bs/edge-contemplating (tick-edge-contemplating state uf-data)
                :bs/dragging (tick-dragging state uf-data)
                :bs/cursor-chasing (tick-cursor-chasing state uf-data)
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
        {:uf/db (assoc state :vel/x clamped-vx :vel/y clamped-vy :buddy/current-surface nil)
         :uf/dxs [[:buddy/ax.enter-state :bs/jumping]]})

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
                                             :rng/roll (rand)
                                             :mouse/x (:mouse/x env)
                                             :mouse/y (:mouse/y env)
                                             :scroll/y (:scroll/y env)
                                             :surfaces/visible (:surfaces/visible env)
                                             :drag/data (:drag/data env)
                                             :env/drag-handler (:env/drag-handler env)
                                             :env/mouse-handler (:env/mouse-handler env)
                                             :env/click-handler (:env/click-handler env)
                                             :env/scroll-handler (:env/scroll-handler env)}
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
                (when-let [env-upd (:uf/env (perform-effect! dispatch! fx))]
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
  (let [frame-interval (/ 1000 (:cfg/fps config))
        tick-interval 100
        !timing (atom {:timing/last-ts nil :timing/frame-accum 0 :timing/tick-accum 0})]
    (fn raf-cb [timestamp]
      (let [{:timing/keys [last-ts frame-accum tick-accum]} @!timing
            dt (if last-ts (min (- timestamp last-ts) 200) 0)
            fa (+ frame-accum dt)
            ta (+ tick-accum dt)
            actions (cond-> []
                      (>= fa frame-interval) (conj [:buddy/ax.advance-frame])
                      (>= ta tick-interval) (conj [:buddy/ax.tick]))]
        (reset! !timing {:timing/last-ts timestamp
                         :timing/frame-accum (if (>= fa frame-interval) (rem fa frame-interval) fa)
                         :timing/tick-accum (if (>= ta tick-interval) (rem ta tick-interval) ta)})
        (when (seq actions)
          (dispatch-fn actions))
        (when @!state
          (dispatch-fn [[:buddy/ax.assoc :buddy/raf-id (js/requestAnimationFrame raf-cb)]]))))))

(defn stop! []
  (dispatch! [[:buddy/ax.stop]]))

(defn start! []
  (when (:buddy/raf-id @!state) (stop!))
  (let [container (js/document.createElement "div")
        el (js/document.createElement "div")]
    (set! (.-id container) "page-buddy-container")
    (set! (.-id el) "page-buddy")
    (.appendChild container el)
    (js/document.body.appendChild container)
    (dispatch! [[:buddy/ax.init {:dom/el el :dom/container container}]])
    (let [raf-cb (make-raf-loop dispatch! !state)
          raf-id (js/requestAnimationFrame raf-cb)]
      (dispatch! [[:buddy/ax.assoc :buddy/raf-id raf-id]]))
    (js/console.log "Page buddy started! 🐱")))

(comment
  (start!)
  (stop!)
  (dispatch! [[:buddy/ax.enter-state :bs/walking]])
  (dispatch! [[:buddy/ax.enter-state :bs/running]])
  (dispatch! [[:buddy/ax.enter-state :bs/jumping]])
  (dispatch! [[:buddy/ax.enter-state :bs/being-hit]])
  (dispatch! [[:buddy/ax.enter-state :bs/sitting]])
  (dispatch! [[:buddy/ax.enter-state :bs/meowing]])
  (dispatch! [[:buddy/ax.enter-state :bs/touching]])
  @!state
  @!env
  (select-keys @!env [:mouse/x :mouse/y])
  :rcf/ok)
