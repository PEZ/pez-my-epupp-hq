(ns youtube-helpers)

(defn get-transcript
  "Returns all transcript segments as a vector of {:time :text} maps.
   Requires the transcript panel to be open."
  []
  (let [segments (js/document.querySelectorAll "ytd-transcript-segment-renderer")]
    (mapv (fn [i]
            (let [el (.item segments i)]
              {:time (some-> (.querySelector el ".segment-timestamp") .-textContent .trim)
               :text (some-> (.querySelector el ".segment-text") .-textContent .trim)}))
          (range (.-length segments)))))

(defn search-transcript
  "Search transcript for segments matching a pattern (string or regex).
   Returns matching segments with their timestamps."
  [pattern]
  (let [re (if (instance? js/RegExp pattern)
             pattern
             (js/RegExp. pattern "i"))
        segments (get-transcript)]
    (filterv #(re-find re (:text %)) segments)))

(defn open-transcript!
  "Opens the transcript panel if not already open."
  []
  (if (pos? (.-length (js/document.querySelectorAll "ytd-transcript-segment-renderer")))
    :already-open
    (do (.click (js/document.querySelector "button[aria-label*='transcript' i]"))
        :opening)))

(defn parse-time
  "Parses a timestamp string like '1:17' or '1:02:30' into seconds."
  [time-str]
  (let [parts (mapv js/parseInt (.split time-str ":"))]
    (case (count parts)
      1 (first parts)
      2 (+ (* 60 (first parts)) (second parts))
      3 (+ (* 3600 (first parts)) (* 60 (second parts)) (nth parts 2))
      0)))

(defn scrub-to!
  "Scrubs the video to a given time. Accepts seconds (number) or a
   timestamp string like '1:17' or '1:02:30'."
  [time]
  (let [seconds (if (number? time) time (parse-time time))
        player (js/document.querySelector "#movie_player")]
    (.seekTo player seconds true)
    {:seeked-to seconds}))

(comment
  (open-transcript!)
  (count (get-transcript))
  (take 3 (get-transcript))
  (search-transcript "simple")
  (search-transcript "i don't care how much")
  (search-transcript #"complect|easy|hard")
  (search-transcript "Clojure")

  (scrub-to! "1:17")
  (scrub-to! "40:14")
  (scrub-to! 120)
  (let [hit (first (search-transcript "i don't care how much"))]
    (scrub-to! (:time hit)))
  :rcf)
