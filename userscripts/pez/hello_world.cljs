{:epupp/script-name "pez/hello_world.cljs"
 :epupp/description "A script saying hello"
 :epupp/auto-run-match "*"
 :epupp/inject ["https://gist.githubusercontent.com/PEZ/f7059fe7328bb25ee3f459d7457dc2a8/raw/50b3bed5fff509c2d86c2cbb4d3fa5f0f47c23ed/pez_test_lib.cljs"]}

(comment
  "https://raw.githubusercontent.com/PEZ/pez-my-epupp-hq/3dbf6393916cd4e384826b093ab6e9a96b1793f9/userscripts/pez/test_lib.cljs"

  "https://gist.githubusercontent.com/PEZ/f7059fe7328bb25ee3f459d7457dc2a8/raw/50b3bed5fff509c2d86c2cbb4d3fa5f0f47c23ed/pez_test_lib.cljs"

  "epupp://pez/test_lib.cljs"
  :rcf)

(ns hello-world
  (:require [pez.test-lib :as lib]))

(defn hello [s]
  (js/console.log "Epupp: Hello" (str s "!")))

(hello "World")

(js/console.log (lib/greeting "Me"))