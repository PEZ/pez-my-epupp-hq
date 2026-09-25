{:epupp/script-name "pez/matrix.cljs"
 :epupp/description "The Matrix's digital rain, frosted so the page shows through. Click, tap, or press a key to leave."
 :epupp/run-at "document-idle"}

(ns pez.matrix)

(def glyphs
  "Half-width katakana, digits and a few symbols, like the code in the film."
  "\uFF71\uFF72\uFF73\uFF74\uFF75\uFF76\uFF77\uFF78\uFF79\uFF7A\uFF7B\uFF7C\uFF7D\uFF7E\uFF7F\uFF80\uFF81\uFF82\uFF83\uFF84\uFF85\uFF86\uFF87\uFF88\uFF89\uFF8A\uFF8B\uFF8C\uFF8D\uFF8E\uFF8F\uFF90\uFF91\uFF92\uFF93\uFF94\uFF95\uFF96\uFF97\uFF98\uFF99\uFF9A\uFF9B\uFF9C\uFF9D0123456789:.=*+-<>\u00A6")

(def cell 18)

(def fallback-paint
  {:bg "rgb(3,26,7)" :trail "rgb(34,168,68)" :head "rgb(217,255,227)"})

(defonce !rain (atom nil))

(defn glyph
  "One character from the rain."
  []
  (.charAt glyphs (rand-int (count glyphs))))

(defn parse-rgb
  "rgb or rgba from getComputedStyle, or nil when missing or transparent."
  [s]
  (when-let [[_ r g b a] (re-matches #"rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)"
                                    (or s ""))]
    (when (or (nil? a)
              (> (js/parseFloat a) 0.05))
      [(js/parseInt r) (js/parseInt g) (js/parseInt b)])))

(defn far-enough?
  "Whether two colours read as different on a canvas."
  [[r1 g1 b1] [r2 g2 b2]]
  (let [dr (- r1 r2)
        dg (- g1 g2)
        db (- b1 b2)]
    (> (+ (* dr dr) (* dg dg) (* db db)) 400)))

(defn luminance
  [[r g b]]
  (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b)))

(defn mix
  "Blend rgb triples, t at 0 staying on a and t at 1 reaching b."
  [a b t]
  (mapv #(js/Math.round (+ %1 (* (- %2 %1) t))) a b))

(defn rgb-css
  [[r g b]]
  (str "rgb(" r "," g "," b ")"))

(defn painted
  "A computed colour of el, or nil."
  [el prop]
  (when el
    (parse-rgb (.getPropertyValue (js/getComputedStyle el) prop))))

(defn colours
  "This page's background, a dimmer trail, and a head that stays readable."
  []
  (let [body (.-body js/document)
        root (.-documentElement js/document)
        bg (or (painted body "background-color")
               (painted root "background-color"))
        fg (or (painted body "color")
               (painted root "color"))]
    (if (and bg fg (far-enough? bg fg))
      (let [head (if (> (luminance bg) (luminance fg))
                   fg
                   (mix fg [255 255 255] 0.75))]
        {:bg (rgb-css bg)
         :trail (rgb-css (mix fg bg 0.55))
         :head (rgb-css head)})
      fallback-paint)))

(defn veil
  "A light wash of a page colour, so the frost stays in the theme."
  [rgb]
  (if-let [[r g b] (parse-rgb rgb)]
    (str "rgba(" r "," g "," b ",0.22)")
    "rgba(0,0,0,0.22)"))

(defn prepare-canvas!
  "Size canvas to the screen, sharp on this display, ready to write glyphs."
  [canvas w h]
  (let [dpr (or (.-devicePixelRatio js/window) 1)
        ctx (.getContext canvas "2d")]
    (set! (.-width canvas) (* w dpr))
    (set! (.-height canvas) (* h dpr))
    (.scale ctx dpr dpr)
    (set! (.-font ctx) (str (- cell 2) "px ui-monospace, monospace"))
    (set! (.-textBaseline ctx) "top")
    ctx))

(defn starts
  "Where each column begins: somewhere above the top of the screen."
  [w h]
  (let [rows (.ceil js/Math (/ h cell))
        n (.ceil js/Math (/ w cell))]
    (into-array (repeatedly n #(- (rand-int rows))))))

(defn fade!
  "Erase the glyphs one step so the page shows through."
  [ctx w h]
  (set! (.-globalCompositeOperation ctx) "destination-out")
  (set! (.-globalAlpha ctx) 0.08)
  (set! (.-fillStyle ctx) "#000")
  (.fillRect ctx 0 0 w h)
  (set! (.-globalAlpha ctx) 1)
  (set! (.-globalCompositeOperation ctx) "source-over"))

(defn fall!
  "Move each column down one cell. A column past the bottom starts again above."
  [ctx drops h clear! {:keys [trail head]}]
  (dotimes [i (alength drops)]
    (let [row (aget drops i)
          x (* i cell)
          y (* row cell)]
      (when (pos? row)
        (clear! x (- y cell))
        (set! (.-fillStyle ctx) trail)
        (.fillText ctx (glyph) x (- y cell)))
      (when (>= row 0)
        (set! (.-fillStyle ctx) head)
        (.fillText ctx (glyph) x y))
      (aset drops i (if (and (> y h)
                             (> (rand) 0.975))
                      (- (rand-int 20))
                      (inc row))))))

(defn leave!
  "Stop the rain and give the page back."
  []
  (when-let [{:keys [stop]} @!rain]
    (reset! !rain nil)
    (stop))
  nil)

(defn plain-key?
  "A key that leaves the rain. Modifier chords stay with the browser."
  [e]
  (not (or (.-metaKey e)
           (.-ctrlKey e)
           (.-altKey e)
           (#{"Shift" "Control" "Alt" "Meta"} (.-key e)))))

(defn matrix!
  "Cover the page with digital rain. A click, a tap, or a key leaves it."
  []
  (leave!)
  (when-let [body (.-body js/document)]
    (let [paint (colours)
          frost (.createElement js/document "div")
          canvas (.createElement js/document "canvas")
          !view (atom nil)
          clear! (fn [x y]
                   (let [{:keys [ctx]} @!view]
                     (.clearRect ctx x y cell cell)))
          step! (fn []
                  (let [{:keys [ctx drops w h]} @!view]
                    (fade! ctx w h)
                    (fall! ctx drops h clear! paint)))
          fit! (fn []
                 (let [w (.-innerWidth js/window)
                       h (.-innerHeight js/window)]
                   (reset! !view {:w w
                                  :h h
                                  :ctx (prepare-canvas! canvas w h)
                                  :drops (starts w h)})))
          still? (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
          settle! (fn []
                    (when still?
                      (dotimes [_ (+ (.ceil js/Math (/ (:h @!view) cell)) 20)]
                        (step!))))
          !frame (atom nil)
          !last (atom 0)
          tick (fn tick [t]
                 (when (>= (- t @!last) 55)
                   (reset! !last t)
                   (step!))
                 (reset! !frame (js/requestAnimationFrame tick)))
          on-key (fn [e]
                   (when (plain-key? e)
                     (.preventDefault e)
                     (.stopImmediatePropagation e)
                     (leave!)))
          on-click (fn [e]
                     (.preventDefault e)
                     (.stopPropagation e)
                     (leave!))
          on-resize (fn []
                      (fit!)
                      (settle!))]
      (set! (.-className frost) "pez-matrix-frost")
      (set! (.. frost -style -cssText)
            (str "position:fixed;inset:0;z-index:2147483646;pointer-events:none;"
                 "background:" (veil (:bg paint)) ";"
                 "backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);"))
      (set! (.-className canvas) "pez-matrix")
      (set! (.. canvas -style -cssText)
            (str "position:fixed;inset:0;width:100%;height:100%;z-index:2147483647;"
                 "cursor:pointer;touch-action:none;background:transparent;"))
      (.setAttribute canvas "role" "img")
      (.setAttribute canvas "aria-label" "The Matrix. Tap, or press any key, to leave.")
      (.addEventListener canvas "click" on-click)
      (.addEventListener js/window "keydown" on-key true)
      (.addEventListener js/window "resize" on-resize)
      (.appendChild body frost)
      (.appendChild body canvas)
      (fit!)
      (settle!)
      (reset! !rain {:stop (fn []
                             (some-> @!frame js/cancelAnimationFrame)
                             (.removeEventListener js/window "keydown" on-key true)
                             (.removeEventListener js/window "resize" on-resize)
                             (.remove frost)
                             (.remove canvas))})
      (when-not still?
        (reset! !frame (js/requestAnimationFrame tick)))
      paint)))

(matrix!)
