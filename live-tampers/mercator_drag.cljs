(ns mercator-drag
  (:require [clojure.string :as str]))

;; === Google Maps Web Mercator projection ===
;; Google Maps uses EPSG:3857 (Web Mercator).
;; At zoom z, the entire world is 256 * 2^z pixels wide and tall.

(defn lng->world-px
  "Convert longitude to world pixel x at given zoom."
  [lng zoom]
  (let [world-size (* 256 (js/Math.pow 2 zoom))]
    (* (/ (+ lng 180) 360) world-size)))

(defn lat->world-px
  "Convert latitude to world pixel y at given zoom (north = smaller y)."
  [lat zoom]
  (let [world-size (* 256 (js/Math.pow 2 zoom))
        lat-rad (* lat (/ js/Math.PI 180))
        sec-lat (/ 1 (js/Math.cos lat-rad))
        y (- 1 (/ (js/Math.log (+ (js/Math.tan lat-rad) sec-lat))
                  js/Math.PI))]
    (* (/ y 2) world-size)))

(defn screen-y->lat
  "Convert a screen Y position back to latitude, given map center and zoom."
  [screen-y center-lat zoom window-h]
  (let [world-size (* 256 (js/Math.pow 2 zoom))
        cy (lat->world-px center-lat zoom)
        world-y (+ cy (- screen-y (/ window-h 2)))
        n (* js/Math.PI (- 1 (* 2 (/ world-y world-size))))]
    (* (/ 180 js/Math.PI) (js/Math.atan (js/Math.sinh n)))))

(defn latlng->screen-px
  "Convert lat/lng to screen pixel position given map center, zoom, and window size."
  [lat lng center-lat center-lng zoom window-w window-h]
  (let [cx (lng->world-px center-lng zoom)
        cy (lat->world-px center-lat zoom)
        px (lng->world-px lng zoom)
        py (lat->world-px lat zoom)]
    {:x (+ (/ window-w 2) (- px cx))
     :y (+ (/ window-h 2) (- py cy))}))

(defn parse-maps-url
  "Parse lat, lng, zoom from a Google Maps URL."
  [url]
  (let [match (.match url #"@(-?[\d.]+),(-?[\d.]+),([\d.]+)z")]
    (when match
      {:lat (js/parseFloat (aget match 1))
       :lng (js/parseFloat (aget match 2))
       :zoom (js/parseFloat (aget match 3))})))

;; === Data loading ===

(def !topo-data (atom nil))

(defn load-topojson!
  "Fetch world-atlas TopoJSON and store in !topo-data atom.
   Calls on-loaded with the data when done."
  [on-loaded]
  (-> (js/fetch "https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json")
      (.then (fn [response] (.json response)))
      (.then (fn [data]
               (reset! !topo-data data)
               (when on-loaded (on-loaded data))))
      (.catch (fn [e]
                (js/console.error "TopoJSON fetch failed:" e)))))

(defn ensure-topojson-client!
  "Inject topojson-client library if not already loaded.
   Calls on-loaded when ready."
  [on-loaded]
  (if (some? js/topojson)
    (on-loaded)
    (let [script (js/document.createElement "script")]
      (set! (.-src script) "https://cdn.jsdelivr.net/npm/topojson-client@3/dist/topojson-client.min.js")
      (set! (.-onload script) on-loaded)
      (.appendChild js/document.head script))))

(defn find-country-feature
  "Find a country feature by name from the loaded TopoJSON data."
  [data country-name]
  (let [all-features (.feature js/topojson data (.. data -objects -countries))]
    (->> (.-features all-features)
         (filter #(= country-name (.. % -properties -name)))
         first)))

;; === Map-aligned SVG with Mercator rescaling on drag ===

(defn render-draggable-country!
  "Render a country as a map-aligned, draggable SVG overlay with Mercator rescaling.
   As you drag the country to different latitudes, it rescales to show its true
   relative size at that latitude."
  [{:keys [feature map-center-lat map-center-lng zoom
           fill stroke stroke-width]
    :or {fill "rgba(0, 106, 167, 0.6)"
         stroke "#fecc00"
         stroke-width "2"}}]
  (let [coords (aget (.. feature -geometry -coordinates) 0)
        window-w (.-innerWidth js/window)
        window-h (.-innerHeight js/window)
        ;; Project each coordinate to screen pixels via Web Mercator
        cx (lng->world-px map-center-lng zoom)
        cy (lat->world-px map-center-lat zoom)
        projected (mapv (fn [coord]
                          (let [px (lng->world-px (aget coord 0) zoom)
                                py (lat->world-px (aget coord 1) zoom)]
                            {:x (+ (/ window-w 2) (- px cx))
                             :y (+ (/ window-h 2) (- py cy))}))
                        coords)
        xs (mapv :x projected)
        ys (mapv :y projected)
        min-x (apply min xs)
        min-y (apply min ys)
        max-x (apply max xs)
        max-y (apply max ys)
        svg-w (- max-x min-x)
        svg-h (- max-y min-y)
        ;; Home center latitude (for rescaling reference)
        home-center-y (/ (+ min-y max-y) 2)
        home-center-lat (screen-y->lat home-center-y map-center-lat zoom window-h)
        ;; SVG polygon points relative to SVG origin
        local-points (mapv (fn [p] {:x (- (:x p) min-x) :y (- (:y p) min-y)}) projected)
        points-str (->> local-points
                        (map #(str (:x %) "," (:y %)))
                        (str/join " "))
        ;; Clean up previous overlay
        _ (when-let [old (js/document.getElementById "country-overlay")]
            (.remove old))
        ;; Create SVG elements
        svg-ns "http://www.w3.org/2000/svg"
        svg (js/document.createElementNS svg-ns "svg")
        polygon (js/document.createElementNS svg-ns "polygon")
        label-el (js/document.createElementNS svg-ns "text")
        ;; Drag state
        drag-state (atom {:dragging false
                          :offset-x 0 :offset-y 0
                          :current-left min-x
                          :current-top min-y})
        update-scale!
        (fn []
          (let [current-top (:current-top @drag-state)
                svg-center-y (+ current-top (/ svg-h 2))
                current-lat (screen-y->lat svg-center-y map-center-lat zoom window-h)
                home-cos (js/Math.cos (* home-center-lat (/ js/Math.PI 180)))
                current-cos (js/Math.cos (* current-lat (/ js/Math.PI 180)))
                scale (/ home-cos current-cos)]
            (set! (.. svg -style -transform) (str "scale(" scale ")"))
            (set! (.-textContent label-el) (str (.toFixed scale 2) "×"))))]
    ;; SVG setup
    (set! (.-id svg) "country-overlay")
    (.setAttribute svg "width" svg-w)
    (.setAttribute svg "height" svg-h)
    (.setAttribute svg "viewBox" (str "0 0 " svg-w " " svg-h))
    (.setAttribute svg "overflow" "visible")
    (set! (.. svg -style -cssText)
          (str "position:fixed;left:" min-x "px;top:" min-y "px;"
               "z-index:99999;cursor:grab;pointer-events:auto;"
               "transform-origin:center center;"
               "filter:drop-shadow(2px 2px 4px rgba(0,0,0,0.5));"))
    ;; Polygon
    (.setAttribute polygon "points" points-str)
    (.setAttribute polygon "fill" fill)
    (.setAttribute polygon "stroke" stroke)
    (.setAttribute polygon "stroke-width" stroke-width)
    ;; Scale label
    (.setAttribute label-el "x" (/ svg-w 2))
    (.setAttribute label-el "y" (/ svg-h 2))
    (.setAttribute label-el "text-anchor" "middle")
    (.setAttribute label-el "dominant-baseline" "middle")
    (.setAttribute label-el "fill" "white")
    (.setAttribute label-el "font-size" "16")
    (.setAttribute label-el "font-weight" "bold")
    (.setAttribute label-el "style" "text-shadow: 1px 1px 3px black;")
    (set! (.-textContent label-el) "1.00×")
    ;; Drag handlers
    (.addEventListener svg "mousedown"
      (fn [e]
        (.preventDefault e)
        (.stopPropagation e)
        (set! (.. svg -style -cursor) "grabbing")
        (swap! drag-state assoc
               :dragging true
               :offset-x (- (.-clientX e) (:current-left @drag-state))
               :offset-y (- (.-clientY e) (:current-top @drag-state)))))
    (.addEventListener js/document "mousemove"
      (fn [e]
        (when (:dragging @drag-state)
          (let [{:keys [offset-x offset-y]} @drag-state
                new-left (- (.-clientX e) offset-x)
                new-top (- (.-clientY e) offset-y)]
            (swap! drag-state assoc :current-left new-left :current-top new-top)
            (set! (.. svg -style -left) (str new-left "px"))
            (set! (.. svg -style -top) (str new-top "px"))
            (update-scale!)))))
    (.addEventListener js/document "mouseup"
      (fn [_]
        (when (:dragging @drag-state)
          (set! (.. svg -style -cursor) "grab")
          (swap! drag-state assoc :dragging false))))
    ;; Assemble
    (.appendChild svg polygon)
    (.appendChild svg label-el)
    (.appendChild js/document.body svg)
    svg))

;; === Rich Comment Forms ===

(comment
  ;; == Setup: load libraries and data ==
  (ensure-topojson-client!
   (fn []
     (load-topojson!
      (fn [data]
        (js/console.log "Ready!"
                        (.-length (.. data -objects -countries -geometries))
                        "countries")))))

  (some? @!topo-data)

  ;; == Explore available countries ==
  (let [geometries (.. @!topo-data -objects -countries -geometries)]
    (->> (range (.-length geometries))
         (mapv #(.. (aget geometries %) -properties -name))
         sort))

  ;; == Inspect a country ==
  (let [feature (find-country-feature @!topo-data "Sweden")
        coords (aget (.. feature -geometry -coordinates) 0)
        lngs (mapv #(aget % 0) coords)
        lats (mapv #(aget % 1) coords)]
    {:name (.. feature -properties -name)
     :type (.. feature -geometry -type)
     :points (count coords)
     :lat-range [(apply min lats) (apply max lats)]
     :lng-range [(apply min lngs) (apply max lngs)]})

  ;; == Current map state ==
  (parse-maps-url (.-href js/location))

  {:title (.-title js/document)
   :url (.-href js/location)
   :window-size [(.-innerWidth js/window) (.-innerHeight js/window)]}

  ;; == Navigate Google Maps to center on a country ==
  ;; Sweden center: ~62.2°N, 17.5°E, zoom 4 for good drag range
  (let [url "https://www.google.com/maps/@30,17.465,3z"]
    (js/setTimeout #(set! (.-href js/location) url) 50)
    (str "Navigating to " url))

  ;; == Render draggable country with Mercator rescaling ==
  ;; After navigation, reload libs:
  (ensure-topojson-client!
   (fn []
     (load-topojson!
      (fn [_] (js/console.log "Ready for rendering!")))))

  ;; Render Sweden (uses current map center/zoom from URL)
  (let [{:keys [lat lng zoom]} (parse-maps-url (.-href js/location))]
    (render-draggable-country!
     {:feature (find-country-feature @!topo-data "Sweden")
      :map-center-lat lat
      :map-center-lng lng
      :zoom zoom}))

  ;; Render Brazil instead
  (let [{:keys [lat lng zoom]} (parse-maps-url (.-href js/location))]
    (render-draggable-country!
     {:feature (find-country-feature @!topo-data "Brazil")
      :map-center-lat lat
      :map-center-lng lng
      :zoom zoom
      :fill "rgba(0, 155, 58, 0.6)"
      :stroke "#ffdf00"}))

  ;; == Cleanup ==
  (when-let [el (js/document.getElementById "country-overlay")]
    (.remove el))

  :rcf)
