(ns cellsify-life
  (:require [clojure.walk :as walk]
            [pez.cellsify :as c]))

;; Game of Life as a Cellsify project. The board is a set of live [x y] in a
;; named cell. One canvas draws the live cells, a click on it toggles a cell,
;; and a timer steps the board while `running` is true.

(def sheet-id 1)

(defn- toolbar-coord [layer x]
  (c/coord sheet-id layer [[1 1] [x 1]]))

(def step-board
  '(fn [b]
     (let [wrap (fn [v] (inc (mod (dec v) size)))]
       (set (for [[c k] (frequencies (for [[x y] b
                                           dx [-1 0 1]
                                           dy [-1 0 1]
                                           :when (not= 0 dx dy)]
                                       [(wrap (+ x dx)) (wrap (+ y dy))]))
                  :when (or (= k 3) (and (= k 2) (contains? b c)))]
              c)))))

(defn- add-toolbar [project {:keys [board running? size interval]}]
  (-> project
      (c/set-input (c/coord sheet-id :f [[1 1]])
                   (c/formula {:control {:type :grid :rows 1 :columns 6}}))
      (c/set-input (toolbar-coord :v 1) (str running?))
      (c/set-input (toolbar-coord :f 1)
                   (c/formula '{:control {:type :button
                                          :text (if L0 "Pause" "Play")
                                          :on-click [{:update 'L0 :formula not}]}}))
      (c/set-input (toolbar-coord :f 2)
                   (c/formula '{:control {:type :button
                                          :text "Clear"
                                          :on-click [{:set 'board :formula (fn [_] #{})}
                                                     {:set 'generation :formula (fn [_] 0)}]}}))
      (c/set-input (toolbar-coord :v 3) (c/formula board))
      (c/set-input (toolbar-coord :f 3) (c/formula '{:display (str (count L0) " alive")}))
      (c/set-input (toolbar-coord :f 4)
                   (c/formula {:control {:type :timer
                                         :interval interval
                                         :repeat true
                                         :stop '(not running)
                                         :on-tick [{:update ''board :formula step-board}
                                                   {:update ''generation :formula 'inc}]}}))
      (c/set-input (toolbar-coord :v 5) (str size))
      (c/set-input (toolbar-coord :f 5) (c/formula '{:display (str L0 " × " L0)}))
      (c/set-input (toolbar-coord :v 6) "0")
      (c/set-input (toolbar-coord :f 6) (c/formula '{:display (str "generation " L0)}))
      (c/name-cell "running" (toolbar-coord :v 1))
      (c/name-cell "board" (toolbar-coord :v 3))
      (c/name-cell "size" (toolbar-coord :v 5))
      (c/name-cell "generation" (toolbar-coord :v 6))))

(def board-format
  '{:control {:type :canvas
              :width (* PX size)
              :height (* PX size)
              :data (into [{:type :rectangle :x 0 :y 0 :width (* PX size) :height (* PX size)
                            :fill-color "#ffffff"}]
                          (for [[x y] board]
                            {:type :rectangle :x (* PX (dec x)) :y (* PX (dec y)) :width PX :height PX
                             :fill-color "#1e293b"}))
              ;; The canvas gives pointer events {:x :y} in its drawing units. A :set :value
              ;; must be a scalar or input text, so the new board goes in as a formula input.
              :on-pointer-down [{:set 'board
                                 :value (fn [{:keys [x y]}]
                                          (let [c [(inc (int (/ x PX))) (inc (int (/ y PX)))]]
                                            (str "=" (pr-str (if (contains? board c) (disj board c) (conj board c))))))}]}})

(defn- add-board [project {:keys [cell-px]}]
  (c/set-input project
               (c/coord sheet-id :f [[1 2]])
               (c/formula (walk/postwalk-replace {'PX cell-px} board-format))))

(defn life-project
  "Returns a Game of Life project with a `size` by `size` board."
  [opts]
  (-> (c/new-project "game-of-life")
      (c/add-sheet {:sheet-id sheet-id :sheet-name "Life" :rows 2 :columns 1})
      (add-toolbar opts)
      (add-board opts)))

(def glider #{[3 1] [4 2] [2 3] [3 3] [4 3]})

(def r-pentomino #{[51 50] [52 50] [50 51] [51 51] [51 52]})

(comment
  ;; Creates game-of-life in the app's project list, pushes the game there, and saves it
  (c/push-new! (c/add-repl-cells (life-project {:board (into glider r-pentomino)
                                                :running? false
                                                :size 100
                                                :interval 150
                                                :cell-px 7})))
  (c/save! "game-of-life")

  ;; Reloading the user content frame stops timers left by earlier pushes
  (-> (c/reload-user-content!)
      (.then #(c/push! (c/add-repl-cells (life-project {:board (into glider r-pentomino)
                                                        :running? false
                                                        :size 100
                                                        :interval 150
                                                        :cell-px 7})))))
  (c/look)
  (c/evaluate '[running generation (count board)])
  :rcf)
