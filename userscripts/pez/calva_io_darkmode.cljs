{:epupp/script-name "pez/calva_io_darkmode.cljs"
 :epupp/description "Darkmode toggle for calva.io"
 :epupp/auto-run-match "https://calva.io*"}

(ns pez.calva-io-darkmode)

(defonce _inject-dark-css
  (let [style-el (js/document.createElement "style")]
    (set! (.-id style-el) "epupp-darkmode")
    (set! (.-textContent style-el)
          "body.epupp-dark {
             --md-default-bg-color: #1a1a2e !important;
             --md-default-fg-color: #e0e0e0 !important;
             --md-typeset-color: #e0e0e0 !important;
             --md-code-bg-color: #16213e !important;
             --md-code-fg-color: #a8dadc !important;
             background-color: #1a1a2e !important;
             color: #e0e0e0 !important;
           }
           body.epupp-dark .md-header {
             background-color: #0f3460 !important;
           }
           body.epupp-dark .md-nav {
             background-color: #1a1a2e !important;
           }
           body.epupp-dark .md-sidebar {
             background-color: #1a1a2e !important;
           }")
    (.appendChild js/document.head style-el)))

(defn toggle-dark-mode! []
  (let [cl (.-classList js/document.body)]
    (if (.contains cl "epupp-dark")
      (do (.remove cl "epupp-dark") "☀️ Light mode")
      (do (.add cl "epupp-dark") "🌙 Dark mode"))))

(toggle-dark-mode!)

