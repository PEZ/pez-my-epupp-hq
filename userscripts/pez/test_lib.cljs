{:epupp/script-name "pez/test_lib.cljs"
 :epupp/description "Test library for injection"
 :epupp/library? true}

(ns pez.test-lib)

(defn greeting [who]
  (str "Hello from pez.test-lib, " who "!"))
