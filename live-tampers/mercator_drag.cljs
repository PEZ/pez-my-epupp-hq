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

(def flag-colors
  "Map of country name to {:fill _ :stroke _} using flag colors.
   Fill is semi-transparent for the country body, stroke is for the border."
  {"Afghanistan" {:fill "rgba(0, 0, 0, 0.5)" :stroke "#d32011"}
   "Albania" {:fill "rgba(227, 0, 15, 0.5)" :stroke "#000"}
   "Algeria" {:fill "rgba(0, 100, 0, 0.5)" :stroke "#d21034"}
   "Argentina" {:fill "rgba(116, 172, 223, 0.5)" :stroke "#f6b40e"}
   "Australia" {:fill "rgba(0, 0, 139, 0.5)" :stroke "#fff"}
   "Austria" {:fill "rgba(237, 41, 57, 0.5)" :stroke "#fff"}
   "Bangladesh" {:fill "rgba(0, 106, 78, 0.5)" :stroke "#f42a41"}
   "Belgium" {:fill "rgba(0, 0, 0, 0.5)" :stroke "#ffd90c"}
   "Bolivia" {:fill "rgba(0, 122, 51, 0.5)" :stroke "#d52b1e"}
   "Brazil" {:fill "rgba(0, 155, 58, 0.6)" :stroke "#ffdf00"}
   "Canada" {:fill "rgba(255, 0, 0, 0.5)" :stroke "#fff"}
   "Chile" {:fill "rgba(0, 57, 166, 0.5)" :stroke "#d52b1e"}
   "China" {:fill "rgba(238, 28, 37, 0.5)" :stroke "#ffde00"}
   "Colombia" {:fill "rgba(252, 209, 22, 0.5)" :stroke "#003893"}
   "Cuba" {:fill "rgba(0, 56, 147, 0.5)" :stroke "#cb1515"}
   "Czechia" {:fill "rgba(17, 69, 126, 0.5)" :stroke "#d7141a"}
   "Denmark" {:fill "rgba(198, 12, 48, 0.5)" :stroke "#fff"}
   "Egypt" {:fill "rgba(0, 0, 0, 0.5)" :stroke "#c8102e"}
   "Estonia" {:fill "rgba(0, 114, 206, 0.5)" :stroke "#000"}
   "Ethiopia" {:fill "rgba(0, 128, 0, 0.5)" :stroke "#fcdd09"}
   "Finland" {:fill "rgba(255, 255, 255, 0.5)" :stroke "#003580"}
   "France" {:fill "rgba(0, 35, 149, 0.5)" :stroke "#ed2939"}
   "Germany" {:fill "rgba(0, 0, 0, 0.5)" :stroke "#ffce00"}
   "Greece" {:fill "rgba(0, 91, 174, 0.5)" :stroke "#fff"}
   "Greenland" {:fill "rgba(255, 255, 255, 0.5)" :stroke "#d00c33"}
   "Hungary" {:fill "rgba(71, 112, 80, 0.5)" :stroke "#ce2939"}
   "Iceland" {:fill "rgba(0, 56, 151, 0.5)" :stroke "#dc1e35"}
   "India" {:fill "rgba(255, 153, 51, 0.5)" :stroke "#046a38"}
   "Indonesia" {:fill "rgba(206, 17, 38, 0.5)" :stroke "#fff"}
   "Iran" {:fill "rgba(35, 159, 64, 0.5)" :stroke "#da0000"}
   "Iraq" {:fill "rgba(206, 17, 38, 0.5)" :stroke "#007a3d"}
   "Ireland" {:fill "rgba(22, 155, 98, 0.5)" :stroke "#ff883e"}
   "Israel" {:fill "rgba(255, 255, 255, 0.5)" :stroke "#0038b8"}
   "Italy" {:fill "rgba(0, 146, 70, 0.5)" :stroke "#ce2b37"}
   "Jamaica" {:fill "rgba(0, 154, 68, 0.5)" :stroke "#fed100"}
   "Japan" {:fill "rgba(255, 255, 255, 0.5)" :stroke "#bc002d"}
   "Kenya" {:fill "rgba(0, 0, 0, 0.5)" :stroke "#bb0000"}
   "Latvia" {:fill "rgba(158, 48, 57, 0.5)" :stroke "#fff"}
   "Lithuania" {:fill "rgba(253, 185, 19, 0.5)" :stroke "#006a44"}
   "Madagascar" {:fill "rgba(0, 126, 58, 0.5)" :stroke "#fc3d32"}
   "Malaysia" {:fill "rgba(0, 0, 115, 0.5)" :stroke "#cc0001"}
   "Mexico" {:fill "rgba(0, 104, 71, 0.5)" :stroke "#ce1126"}
   "Mongolia" {:fill "rgba(0, 98, 178, 0.5)" :stroke "#c4272f"}
   "Morocco" {:fill "rgba(193, 39, 45, 0.5)" :stroke "#006233"}
   "Nepal" {:fill "rgba(220, 20, 60, 0.5)" :stroke "#003893"}
   "Netherlands" {:fill "rgba(33, 70, 139, 0.5)" :stroke "#ae1c28"}
   "New Zealand" {:fill "rgba(0, 0, 107, 0.5)" :stroke "#c8102e"}
   "Nigeria" {:fill "rgba(0, 128, 0, 0.5)" :stroke "#fff"}
   "North Korea" {:fill "rgba(237, 28, 36, 0.5)" :stroke "#024fa2"}
   "Norway" {:fill "rgba(186, 12, 47, 0.5)" :stroke "#002868"}
   "Pakistan" {:fill "rgba(1, 65, 30, 0.5)" :stroke "#fff"}
   "Peru" {:fill "rgba(217, 16, 35, 0.5)" :stroke "#fff"}
   "Philippines" {:fill "rgba(0, 56, 168, 0.5)" :stroke "#ce1127"}
   "Poland" {:fill "rgba(220, 20, 60, 0.5)" :stroke "#fff"}
   "Portugal" {:fill "rgba(0, 102, 0, 0.5)" :stroke "#ff0000"}
   "Romania" {:fill "rgba(0, 43, 127, 0.5)" :stroke "#fcd116"}
   "Russia" {:fill "rgba(0, 57, 166, 0.5)" :stroke "#d52b1e"}
   "Saudi Arabia" {:fill "rgba(0, 106, 78, 0.5)" :stroke "#fff"}
   "South Africa" {:fill "rgba(0, 119, 73, 0.5)" :stroke "#ffb612"}
   "South Korea" {:fill "rgba(255, 255, 255, 0.5)" :stroke "#c60c30"}
   "Spain" {:fill "rgba(170, 21, 27, 0.5)" :stroke "#f1bf00"}
   "Sri Lanka" {:fill "rgba(138, 21, 56, 0.5)" :stroke "#ffb700"}
   "Sudan" {:fill "rgba(0, 114, 41, 0.5)" :stroke "#d21034"}
   "Sweden" {:fill "rgba(0, 106, 167, 0.6)" :stroke "#fecc00"}
   "Switzerland" {:fill "rgba(218, 41, 28, 0.5)" :stroke "#fff"}
   "Taiwan" {:fill "rgba(0, 0, 149, 0.5)" :stroke "#fe0000"}
   "Thailand" {:fill "rgba(45, 45, 116, 0.5)" :stroke "#a51931"}
   "Turkey" {:fill "rgba(227, 10, 23, 0.5)" :stroke "#fff"}
   "Ukraine" {:fill "rgba(0, 87, 183, 0.5)" :stroke "#ffd700"}
   "United Arab Emirates" {:fill "rgba(0, 115, 47, 0.5)" :stroke "#ff0000"}
   "United Kingdom" {:fill "rgba(0, 36, 125, 0.5)" :stroke "#c8102e"}
   "United States of America" {:fill "rgba(60, 59, 110, 0.5)" :stroke "#b22234"}
   "Uruguay" {:fill "rgba(0, 56, 168, 0.5)" :stroke "#fff"}
   "Venezuela" {:fill "rgba(0, 36, 125, 0.5)" :stroke "#cf142b"}
   "Vietnam" {:fill "rgba(218, 37, 29, 0.5)" :stroke "#ffcd00"}})

;; === Map-aligned SVG with Mercator rescaling on drag ===

(defn render-draggable-country!
  "Render a country as a map-aligned, draggable SVG overlay with per-vertex
   Mercator rescaling. Each vertex is reprojected individually based on its
   shifted latitude, producing accurate shape warping across latitudes.
   Supports both Polygon and MultiPolygon geometries."
  [{:keys [feature map-center-lat map-center-lng zoom
           fill stroke stroke-width]
    :or {stroke-width "2"}}]
  (let [country-name (.. feature -properties -name)
        {:keys [fill stroke]} (merge (get flag-colors country-name
                                          {:fill "rgba(100, 100, 100, 0.5)"
                                           :stroke "#fff"})
                                     (when fill {:fill fill})
                                     (when stroke {:stroke stroke}))
        geo-type (.. feature -geometry -type)
        geo-coords (.. feature -geometry -coordinates)
        ;; Extract all outer rings as Clojure vectors of [lng lat]
        raw-rings (if (= "MultiPolygon" geo-type)
                    (mapv (fn [i]
                            (let [ring (aget (aget geo-coords i) 0)]
                              (mapv (fn [j] [(aget (aget ring j) 0)
                                             (aget (aget ring j) 1)])
                                    (range (.-length ring)))))
                          (range (.-length geo-coords)))
                    (let [ring (aget geo-coords 0)]
                      [(mapv (fn [j] [(aget (aget ring j) 0)
                                      (aget (aget ring j) 1)])
                             (range (.-length ring)))]))
        ;; Home center lat/lng from all vertices
        all-lats (mapcat (fn [ring] (map second ring)) raw-rings)
        all-lngs (mapcat (fn [ring] (map first ring)) raw-rings)
        home-center-lat (/ (+ (apply min all-lats) (apply max all-lats)) 2)
        home-center-lng (/ (+ (apply min all-lngs) (apply max all-lngs)) 2)
        window-w (.-innerWidth js/window)
        window-h (.-innerHeight js/window)
        world-size (* 256 (js/Math.pow 2 zoom))
        map-cx (lng->world-px map-center-lng zoom)
        map-cy (lat->world-px map-center-lat zoom)
        ;; Project all rings given a lat/lng offset, with per-vertex
        ;; longitude scaling to preserve physical (great-circle) width.
        ;; Each vertex's lng offset from center is scaled by
        ;; cos(original_lat) / cos(shifted_lat), so high-latitude
        ;; features contract east-west when dragged toward the equator.
        deg->rad (/ js/Math.PI 180)
        max-lat 85.05  ;; Web Mercator limit
        clamp-lat (fn [lat] (max (- max-lat) (min max-lat lat)))
        project-all
        (fn [delta-lat delta-lng]
          (let [new-center-lng (+ home-center-lng delta-lng)]
            (mapv (fn [ring]
                    (mapv (fn [[lng lat]]
                            (let [new-lat (clamp-lat (+ lat delta-lat))
                                  ;; Preserve physical east-west distance
                                  lng-from-center (- lng home-center-lng)
                                  cos-ratio (/ (js/Math.cos (* lat deg->rad))
                                               (js/Math.cos (* new-lat deg->rad)))
                                  new-lng (+ new-center-lng (* lng-from-center cos-ratio))
                                  px (lng->world-px new-lng zoom)
                                  py (lat->world-px new-lat zoom)]
                              {:x (+ (/ window-w 2) (- px map-cx))
                               :y (+ (/ window-h 2) (- py map-cy))}))
                          ring))
                  raw-rings)))
        ;; Initial projection (home position)
        home-projected (project-all 0 0)
        home-all-pts (apply concat home-projected)
        home-xs (mapv :x home-all-pts)
        home-ys (mapv :y home-all-pts)
        home-min-x (apply min home-xs)
        home-min-y (apply min home-ys)
        home-max-x (apply max home-xs)
        home-max-y (apply max home-ys)
        home-svg-w (- home-max-x home-min-x)
        home-svg-h (- home-max-y home-min-y)
        home-screen-cx (/ (+ home-min-x home-max-x) 2)
        home-screen-cy (/ (+ home-min-y home-max-y) 2)
        ;; Clean up previous overlay
        _ (when-let [old (js/document.getElementById "country-overlay")]
            (.remove old))
        ;; Create SVG
        svg-ns "http://www.w3.org/2000/svg"
        svg (js/document.createElementNS svg-ns "svg")
        label-el (js/document.createElementNS svg-ns "text")
        polygon-els (mapv (fn [_] (js/document.createElementNS svg-ns "polygon")) raw-rings)
        ;; Drag state: track center screen position
        drag-state (atom {:dragging false
                          :offset-x 0 :offset-y 0
                          :center-sx home-screen-cx
                          :center-sy home-screen-cy})
        ;; Reproject and update SVG
        update-shape!
        (fn []
          (let [{:keys [center-sx center-sy]} @drag-state
                ;; Convert screen center to lat/lng
                current-center-lat (screen-y->lat center-sy map-center-lat zoom window-h)
                world-x (+ map-cx (- center-sx (/ window-w 2)))
                current-center-lng (- (* (/ world-x world-size) 360) 180)
                ;; Lat/lng offset from home
                delta-lat (- current-center-lat home-center-lat)
                delta-lng (- current-center-lng home-center-lng)
                ;; Reproject all vertices
                projected (project-all delta-lat delta-lng)
                all-pts (apply concat projected)
                xs (mapv :x all-pts)
                ys (mapv :y all-pts)
                mn-x (apply min xs)
                mn-y (apply min ys)
                mx-x (apply max xs)
                mx-y (apply max ys)
                svg-w (- mx-x mn-x)
                svg-h (- mx-y mn-y)
                ;; Scale label: area ratio at center
                home-cos (js/Math.cos (* home-center-lat (/ js/Math.PI 180)))
                current-cos (js/Math.cos (* current-center-lat (/ js/Math.PI 180)))
                scale (/ home-cos current-cos)]
            ;; Update SVG geometry
            (.setAttribute svg "width" svg-w)
            (.setAttribute svg "height" svg-h)
            (.setAttribute svg "viewBox" (str "0 0 " svg-w " " svg-h))
            (set! (.. svg -style -left) (str mn-x "px"))
            (set! (.. svg -style -top) (str mn-y "px"))
            ;; Update polygon points
            (doseq [[ring-pts poly-el] (map vector projected polygon-els)]
              (.setAttribute poly-el "points"
                             (->> ring-pts
                                  (map #(str (- (:x %) mn-x) "," (- (:y %) mn-y)))
                                  (clojure.string/join " "))))
            ;; Update label
            (.setAttribute label-el "x" (/ svg-w 2))
            (.setAttribute label-el "y" (/ svg-h 2))
            (set! (.-textContent label-el) (str (.toFixed scale 2) "×"))))]
    ;; SVG setup
    (set! (.-id svg) "country-overlay")
    (.setAttribute svg "width" home-svg-w)
    (.setAttribute svg "height" home-svg-h)
    (.setAttribute svg "viewBox" (str "0 0 " home-svg-w " " home-svg-h))
    (.setAttribute svg "overflow" "visible")
    (set! (.. svg -style -cssText)
          (str "position:fixed;left:" home-min-x "px;top:" home-min-y "px;"
               "z-index:99999;cursor:grab;pointer-events:auto;"
               "filter:drop-shadow(2px 2px 4px rgba(0,0,0,0.5));"))
    ;; Initial polygon rendering
    (doseq [[ring-pts poly-el] (map vector home-projected polygon-els)]
      (.setAttribute poly-el "points"
                     (->> ring-pts
                          (map #(str (- (:x %) home-min-x) "," (- (:y %) home-min-y)))
                          (clojure.string/join " ")))
      (.setAttribute poly-el "fill" fill)
      (.setAttribute poly-el "stroke" stroke)
      (.setAttribute poly-el "stroke-width" stroke-width)
      (.appendChild svg poly-el))
    ;; Scale label (initial)
    (.setAttribute label-el "x" (/ home-svg-w 2))
    (.setAttribute label-el "y" (/ home-svg-h 2))
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
                                :offset-x (- (.-clientX e) (:center-sx @drag-state))
                                :offset-y (- (.-clientY e) (:center-sy @drag-state)))))
    (.addEventListener js/document "mousemove"
                       (fn [e]
                         (when (:dragging @drag-state)
                           (let [{:keys [offset-x offset-y]} @drag-state
                                 new-cx (- (.-clientX e) offset-x)
                                 new-cy (- (.-clientY e) offset-y)]
                             (swap! drag-state assoc :center-sx new-cx :center-sy new-cy)
                             (update-shape!)))))
    (.addEventListener js/document "mouseup"
                       (fn [_]
                         (when (:dragging @drag-state)
                           (set! (.. svg -style -cursor) "grab")
                           (swap! drag-state assoc :dragging false))))
    ;; Assemble
    (.appendChild svg label-el)
    (.appendChild js/document.body svg)
    svg))

(defn render! [country]
  (let [{:keys [lat lng zoom]} (parse-maps-url (.-href js/location))]
      (render-draggable-country!
       {:feature (find-country-feature @!topo-data country)
        :map-center-lat lat
        :map-center-lng lng
        :zoom zoom})))

;; === Rich Comment Forms ===

(comment
  ;; (Run this after each page navigation — REPL state resets on reload)
  (ensure-topojson-client!
   (fn []
     (load-topojson!
      (fn [data]
        (js/console.log "Ready!"
                        (.-length (.. data -objects -countries -geometries))
                        "countries")))))

  (some? @!topo-data)

  ;; Uses the current map center/zoom from the URL.
  ;; Drag the shape to different latitudes to see Mercator rescaling!
  (render! "Sweden")
  (render! "Brazil")
  (render! "Japan")
  (render! "United States of America")
  (render! "Algeria")


  ;; == Explore ==
  (let [geometries (.. @!topo-data -objects -countries -geometries)]
    (->> (range (.-length geometries))
         (mapv #(.. (aget geometries %) -properties -name))
         sort))

  (let [feature (find-country-feature @!topo-data "Sweden")
        coords (aget (.. feature -geometry -coordinates) 0)
        lngs (mapv #(aget % 0) coords)
        lats (mapv #(aget % 1) coords)]
    {:name (.. feature -properties -name)
     :type (.. feature -geometry -type)
     :points (count coords)
     :lat-range [(apply min lats) (apply max lats)]
     :lng-range [(apply min lngs) (apply max lngs)]})

  ;; == Navigate to different views ==
  ;; Zoom 3 centered at 30°N shows Scandinavia to southern Africa
  (let [url "https://www.google.com/maps/@30,15,3z?force=pwa&source=mldp"]
    (js/setTimeout #(set! (.-href js/location) url) 50)
    (str "Navigating to " url))

  ;; Zoom 5 centered on Sweden for close-up alignment check
  (let [url "https://www.google.com/maps/@62.235,17.465,5z?force=pwa&source=mldp"]
    (js/setTimeout #(set! (.-href js/location) url) 50)
    (str "Navigating to " url))

  ;; == Map state ==
  (parse-maps-url (.-href js/location))

  {:title (.-title js/document)
   :url (.-href js/location)
   :window-size [(.-innerWidth js/window) (.-innerHeight js/window)]}

  ;; == Cleanup ==
  (when-let [el (js/document.getElementById "country-overlay")]
    (.remove el))


  ;; Ensure flat Mercator map view ==
  ;; Google Maps can get stuck in Earth/Globe mode.
  ;; Appending ?force=pwa&source=mldp to the URL forces flat 2D Mercator.
  ;; Also: the Layers panel has a "Globe view" toggle to switch back.
  (let [url "https://www.google.com/maps/@30,15,3z?force=pwa&source=mldp"]
    (js/setTimeout #(set! (.-href js/location) url) 50)
    (str "Navigating to " url))
  :rcf)
