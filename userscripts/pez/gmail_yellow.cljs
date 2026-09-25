{:epupp/script-name "pez/gmail_yellow.cljs"
 :epupp/description "Yellow stars and importance markers on Gmail"
 :epupp/auto-run-match "https://mail.google.com/*"}

(ns pez.gmail-yellow)

(def yellow-css
  ".T-KT.Uieduc,
.vOtjfb.Ki81zb,
.pH.Uieduc {
  filter: brightness(0) invert(78%) sepia(68%) saturate(1800%) hue-rotate(8deg) brightness(1.06);
}")

(defn install-yellow!
  "Installs the yellow star and marker stylesheet."
  []
  (let [style-el (or (js/document.getElementById "epupp-gmail-yellow")
                     (let [el (js/document.createElement "style")]
                       (set! (.-id el) "epupp-gmail-yellow")
                       (.appendChild js/document.head el)
                       el))]
    (set! (.-textContent style-el) yellow-css)
    :installed))

(install-yellow!)
