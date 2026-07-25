(ns book
  "Turn the geometry-rich Litteraturbanken EDN into Markdown, then PDF/EPUB
   via pandoc.

   The downloader deliberately keeps each line's `left`/`right`/`top`, because
   that geometry is the only surviving record of the printed layout. Everything
   here is a pure reading of those coordinates:

     - a line indented past the page's median `left`  => starts a paragraph
     - a first line whose text recurs across pages    => running header, drop
     - a roman-numeral-only line                      => chapter heading

   Hyphenation is resolved by character class: a soft hyphen (U+00AD) is always
   a syllable break, while a plain '-' is a syllable break before a lowercase
   letter and a genuine compound before an uppercase one (Holstein-Gottorpska)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- pure core

(defn median [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (when (pos? n) (nth v (quot n 2)))))

(def ^:private numeral-shape-re #"^[IVXLCJMivxlcjm.\s]+$")

(def ^:private canonical-roman-re #"^C{0,3}(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

(defn chapter-numeral
  "The decoded numeral if `text` reads as a chapter number, else nil.

   The scans misread roman numerals in consistent ways: three strokes `III`
   come back as `m`, `I` as `J`, and stray spaces and dots creep in — so
   `vm .`, `XL VI .` and `Lxxm .` are VIII, XLVI and LXXIII. Position (see
   `mark-structure`) is what makes decoding this loosely safe."
  [text]
  (let [t (str/trim (or text ""))]
    (when (and (seq t) (<= (count t) 12) (re-matches numeral-shape-re t))
      (let [n (-> t
                  str/upper-case
                  (str/replace #"[^IVXLCJM]" "")
                  (str/replace "J" "I")
                  (str/replace "M" "III"))]
        (when (and (seq n) (re-matches canonical-roman-re n)) n)))))

(defn norm-head
  "Normalize a first line for recurrence comparison: drop the page number and
   any non-letters, so `8 TILL LÄSAREN .` and `TILL LÄSAREN . 9` collapse."
  [t]
  (-> (or t "")
      (str/replace #"^\d+\s+" "")
      (str/replace #"\s+\d+$" "")
      (str/replace #"[^\p{L} ]" "")
      (str/replace #"\s+" " ")
      str/trim
      str/upper-case))

(defn page-lines
  "A page's lines in printed order."
  [page]
  (vec (sort-by :top (:lines page))))

(defn running-head-patterns
  "Normalized first-line texts that recur on `min-count`+ pages. Chapter markers
   never recur, so they are never caught by this."
  [pages & {:keys [min-count] :or {min-count 3}}]
  (->> pages
       (keep #(some-> (first (page-lines %)) :text norm-head not-empty))
       frequencies
       (keep (fn [[k v]] (when (>= v min-count) k)))
       set))

(defn page-number-offset
  "Most common (printed page number - sida) across first lines carrying a
   number, e.g. -32 for this volume."
  [pages]
  (->> pages
       (keep (fn [p]
               (let [t (:text (first (page-lines p)))
                     d (or (second (re-find #"^(\d+)\s+\S" (or t "")))
                           (second (re-find #"\s(\d+)$" (or t ""))))]
                 (when d (- (parse-long d) (:page p))))))
       frequencies
       (sort-by val >)
       ffirst))

(defn- running-head?
  [{:keys [patterns offset]} page text]
  (let [norm (norm-head text)
        num  (or (second (re-find #"^(\d+)\s+\S" (or text "")))
                 (second (re-find #"\s(\d+)$" (or text ""))))]
    (boolean
     (or (contains? patterns norm)
         (and num offset (<= (abs (- (parse-long num) (+ page offset))) 1))))))

(defn strip-running-head
  "Drop a page's first line when it is a running header."
  [ctx page]
  (let [ls (page-lines page)]
    (if (and (seq ls) (running-head? ctx (:page page) (:text (first ls))))
      (subvec ls 1)
      ls)))

(defn place-lines
  "Classify each line by geometry. Done per page: the overlay's coordinate
   origin differs from scan to scan, so only distances *within* a page mean
   anything.

   An indented line that still reaches the right margin opens a paragraph; an
   indented line falling well short of it is centered — a title."
  [lines indent]
  (let [med    (median (keep :left lines))
        rights (keep :right lines)
        maxr   (when (seq rights) (apply max rights))
        thr    (when med (+ med indent))]
    (mapv (fn [l]
            (let [indented? (boolean (and thr (:left l) (> (:left l) thr)))
                  short? (boolean (and maxr (:right l)
                                       (< (:right l) (- maxr (* 3 indent)))))]
              (assoc l
                     :para-start? indented?
                     :centered? (and indented? short?))))
          lines)))

(defn mark-structure
  "Classify a page's lines and, if it opens a chapter, fold the leading centered
   run into one heading.

   A chapter opener is recognised two ways, because the scans corrupt each
   signal on different pages: the run may start with a decodable numeral, or its
   title may be one of the book's running heads (a chapter's title reappears as
   the running head on its continuation pages). Either alone leaves gaps; taken
   together they cover the volume."
  [ctx lines & {:keys [indent] :or {indent 8}}]
  (let [placed (place-lines lines indent)
        lead (vec (take-while :centered? placed))
        numeral (chapter-numeral (:text (first lead)))
        title-lines (if numeral (subvec lead 1) lead)
        title (->> title-lines (map (comp str/trim :text)) (str/join " "))
        opener? (boolean (and (seq lead)
                              (seq title-lines)
                              (or numeral
                                  (contains? (:patterns ctx) (norm-head title)))))]
    (if-not opener?
      placed
      (into [(assoc (first lead)
                    :heading? true
                    :heading-text (str (when numeral (str numeral ". ")) title))]
            (concat (map #(assoc % :consumed? true) (rest lead))
                    (drop (count lead) placed))))))

(defn join-parts
  "Join a paragraph's lines, resolving end-of-line hyphenation."
  [parts]
  (reduce (fn [acc s]
            (cond
              (str/blank? acc) s
              (str/ends-with? acc "\u00ad") (str (subs acc 0 (dec (count acc))) s)
              (str/ends-with? acc "-") (if (re-find #"^\p{Lu}" s)
                                         (str acc s)
                                         (str (subs acc 0 (dec (count acc))) s))
              :else (str acc " " s)))
          ""
          parts))

(defn fix-spacing
  "Repair the OCR layer's spacing around punctuation, and drop the placeholder
   glyphs the OCR emits for characters it could not read."
  [s]
  (-> (or s "")
      (str/replace #"[\u25a0\u2666]" "")
      (str/replace #"\s+([,.;:!?»])" "$1")
      (str/replace #"([«(])\s+" "$1")
      (str/replace #"\s+\)" ")")
      (str/replace #"\s{2,}" " ")
      str/trim))

(defn tighten-digits
  "Collapse OCR-spaced digit runs, e.g. `1 7 7 5` => `1775`. Headings only."
  [s]
  (str/replace (or s "") #"(?<=\d) (?=\d)" ""))

(defn lines->blocks
  "Fold a flat line sequence into {:type :heading|:para} blocks. A chapter
   numeral absorbs the centered title lines that follow it."
  [lines]
  (loop [[l & more] lines
         cur nil
         out []]
    (if-not l
      (cond-> out cur (conj cur))
      (cond
        (:consumed? l)
        (recur more cur out)

        (:heading? l)
        (recur more
               nil
               (cond-> out
                 cur (conj cur)
                 :always (conj {:type :heading :text (:heading-text l)})))

        (or (nil? cur) (:para-start? l))
        (recur more {:type :para :parts [(:text l)]} (cond-> out cur (conj cur)))

        :else
        (recur more (update cur :parts conj (:text l)) out)))))

(defn book->blocks
  "Whole book as blocks. Paragraphs flow across page boundaries."
  [pages]
  (let [ctx {:patterns (running-head-patterns pages)
             :offset (page-number-offset pages)}]
    (->> pages
         (mapcat #(mark-structure ctx (strip-running-head ctx %)))
         lines->blocks)))

(defn escape-block-markers
  "Keep Markdown from re-reading the text as block syntax. Footnote marks like
   `*` and `* * *`, or a paragraph opening `1775.`, would otherwise become list
   items — and enough of them nested in a row overflows LaTeX."
  [s]
  (-> s
      (str/replace #"^([-*+>#])" "\\\\$1")
      (str/replace #"^(\d+)([.)])(\s)" "$1\\\\$2$3")))

(defn block->markdown [{:keys [type text parts]}]
  (case type
    :heading (str "## " (-> text fix-spacing tighten-digits))
    :para (-> parts join-parts fix-spacing escape-block-markers)))

(defn yaml-front-matter [{:keys [title author lang]}]
  (str "---\n"
       "title: \"" title "\"\n"
       "author: \"" author "\"\n"
       "lang: " lang "\n"
       "---\n"))

(defn parse-ranges
  "\"21-32,478\" => #{21 22 ... 32 478}"
  [s]
  (let [s (str/trim (str (or s "")))]
    (when (seq s)
      (into #{}
            (mapcat (fn [part]
                      (let [[a b] (str/split (str/trim part) #"-")]
                        (if b
                          (range (parse-long a) (inc (parse-long b)))
                          [(parse-long a)]))))
            (str/split s #",")))))

(defn book->markdown
  [pages {:keys [from to skip] :as meta}]
  (let [skipped (parse-ranges skip)
        sel (cond->> pages
              from (filter #(>= (:page %) from))
              to (filter #(<= (:page %) to))
              skipped (remove #(contains? skipped (:page %))))]
    (str (yaml-front-matter meta)
         "\n"
         (->> (book->blocks sel)
              (map block->markdown)
              (remove str/blank?)
              (str/join "\n\n"))
         "\n")))

;; ------------------------------------------------------------ imperative shell

(def ^:private defaults
  "Tuned for this volume: pages 1-10 are covers and plates, and 21-32 are the
   printed table of contents, which pandoc regenerates from the headings."
  {:in "data/hedvig-dagbok-1-lines.edn"
   :out-dir "build"
   :name "hedvig-dagbok-1"
   :title "Hedvig Elisabeth Charlottas dagbok I (1775\u20131782)"
   :author "Hedvig Elisabeth Charlotta"
   :lang "sv"
   :format "both"
   :from 11
   :skip "21-32"})

(defn- pandoc! [args]
  (println "  pandoc" (str/join " " args))
  (let [{:keys [exit]} (apply p/shell {:continue true} "pandoc" args)]
    (when-not (zero? exit)
      (println "  pandoc failed, exit" exit))
    (zero? exit)))

(defn generate!
  "Render the EDN to Markdown, then to PDF and/or EPUB via pandoc."
  [opts]
  (let [{:keys [in out-dir] base :name fmt :format :as o} (merge defaults opts)
        pages (edn/read-string (slurp in))
        md-path (str (fs/path out-dir (str base ".md")))
        markdown (book->markdown pages o)
        want (case fmt
               "pdf" #{:pdf}
               "epub" #{:epub}
               #{:pdf :epub})]
    (fs/create-dirs out-dir)
    (spit md-path markdown)
    (println (format "Markdown: %s (%d chars)" md-path (count markdown)))
    (cond-> {:md md-path}
      (:pdf want)
      (assoc :pdf (let [out (str (fs/path out-dir (str base ".pdf")))]
                    (when (pandoc! [md-path "-o" out "--pdf-engine=xelatex"
                                    "--toc" "--toc-depth=1"
                                    "-V" "documentclass=book" "-V" "geometry:margin=2.5cm"])
                      out)))

      (:epub want)
      (assoc :epub (let [out (str (fs/path out-dir (str base ".epub")))]
                     (when (pandoc! [md-path "-o" out "--toc" "--toc-depth=1"])
                       out))))))

(defn make-book
  "Task entry."
  [opts]
  (let [{:keys [md pdf epub]} (generate! opts)]
    (println "Done." (str/join ", " (remove nil? [md pdf epub])))))
