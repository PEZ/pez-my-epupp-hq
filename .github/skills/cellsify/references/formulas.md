# Formulas, controls, and actions

What we know about writing inputs for Cellsify. Learned from `live-tampers/cellsify_life.cljs`, the REPL cells in `userscripts/pez/cellsify.cljs`, and probes through `evaluate`. Cellsify does not document this, so add to it when you learn more.

## Inputs

An input is the text in one layer of one cell.

- A **plain input** is a scalar: `"100"`, `"false"`, `"hello"`. Collections in a plain input do not parse.
- A **formula input** is `=` followed by a printed form. `(formula form)` builds one: `(formula '(+ 1 2))` is `"=(+ 1 2)"`. Collections go here: `(formula #{[3 1] [4 2]})`.
- The **value layer** `:v` holds the cell's value. The **format layer** `:f` holds a map with `:control`, `:display`, and `:style`.
- `:display` replaces what the cell shows: `{:display (str (count L0) " alive")}`.

Formulas run sandboxed in the `user` namespace. `js/` interop does not resolve (`"Unable to resolve symbol: js/Date.now"`). Available namespaces include `cellsify`, `cellsify.controls`, `cellsify.control`, `cellsify.canvas`, `cellsify.graph`, `cellsify.csv`, `cellsify.json`, `cellsify.time`, `cellsify.debug`, `cellsify.exactly`, `cellsify.bigint`, and `spreadsheet.frontend.xl`.

## Refs

- A **named ref** reaches a cell by name. `(name-cell project "board" coord)` makes `board` usable in any formula, and in `evaluate`.
- `X9Y10` reaches the cell at column 9, row 10 of the sheet.
- `L1` and `R1` reach the cell to the left and to the right. `L0` is the cell itself: a format layer reads its own cell's value with `L0`, as Life's Play button does.
- Inside the cells of a grid, `X` and `Y` are the cell's own column and row.

A formula that reads an erroring cell errors too. Wrap code that may throw in `try`, the way the eval cell does.

## Controls

A control goes in the format layer under `:control`. These are the ones we have used, with the keys we have used, shown as they read in the formula text:

```clojure
{:control {:type :grid :rows 1 :columns 6}}

{:control {:type :button
           :text (if L0 "Pause" "Play")
           :on-click [{:update 'L0 :formula not}]}}

{:control {:type :timer
           :interval 150
           :repeat true
           :stop (not running)
           :on-tick [{:update 'generation :formula inc}]}}

{:control {:type :canvas
           :width 700 :height 700
           :data [{:type :rectangle :x 0 :y 0 :width 7 :height 7 :fill-color "#1e293b"}]
           :on-pointer-down [{:set 'board :value (fn [{:keys [x y]}] ...)}]}}
```

- `:grid` nests a grid in the cell. Reach its cells with a longer path: `[[1 1] [3 1]]` is cell 3,1 of the grid in cell 1,1.
- A canvas takes its pointer actions inside `:control`: `:on-click`, `:on-pointer-down`, `:on-pointer-up`, `:on-pointer-move`. The event's value is `{:x :y :hits}`, with `x` and `y` in the canvas's drawing units. The canvas stops pointer events from reaching the cell, so an `:on-pointer-down` beside `:control` never fires on a canvas.
- Beside `:control`, `:on-pointer-down` is a grid cell event, with `{:pointer-x :pointer-y :width :height :left :top}` as its value. Only cells without a canvas get it.
- Timers tick in the hidden var sheet and in inactive sheets too.

## Actions

Controls fire actions from events: `:on-click`, `:on-tick`, `:on-pointer-down`, and `:on-response`. Each action is a map.

- `{:update target :formula f}` calls `f` with the target's old value and stores the result.
- `{:set target :formula f}` stores the result of `f` as a formula input, so collections are fine: `{:set 'board :formula (fn [_] #{})}` writes `"=#{}"`.
- `{:set target :value f}` calls `f` with the event's value, such as the pointer data from a canvas or the reply in `:on-response`, and stores the result as input text. A string starting with `=` becomes a formula input, and nil clears the target. A collection is rejected with "Value not allowed, try setting it as formula", so Life's canvas returns `(str "=" (pr-str new-board))`.
- Results of `:formula` land as formula inputs, so `pull` shows them as `"=13"` or `"=#{...}"`.
- `{:type :api-server :request expr :on-response [actions]}` sends `expr` to the app as a `:local-server-request`. `serve!` installs the handler that answers, and the reply is the event's value for the `:on-response` actions. A nil reply arrives as `{:error nil}`, so an `:on-response` that writes the reply with `:set :value` must turn non-strings into nil first.
- `cellsify/eval` and `load-string` throw "No context found" inside an action's function. The evaluator only exists while a formula evaluates, which is why the REPL cells write the form into the eval cell as a formula.

## Quoting targets

The formula text is the printed form, and an action's target must print as a quoted ref, `(quote board)`.

- Inside a quoted template, write `'board`: `'{:control {... :on-click [{:update 'L0 :formula not}]}}`.
- Inside a map built unquoted, so that Clojure values can be spliced in, write `''board`, as Life's timer and the REPL timer do.
- To parameterize a quoted template, replace placeholder symbols with `clojure.walk/postwalk-replace`, as Life does with `PX`.
