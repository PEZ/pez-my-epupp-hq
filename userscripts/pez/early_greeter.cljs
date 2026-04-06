{:epupp/script-name "pez/early_greeter.cljs"
 :epupp/auto-run-match "*"
 :epupp/run-at "document-start"
 :epupp/description "Tests that a library works at document-start"
 :epupp/inject ["epupp://pez/test_lib.cljs"]}

(ns pez.early-greeter
  (:require [pez.test-lib :as lib]))

(js/console.log (lib/greeting "document-start"))
