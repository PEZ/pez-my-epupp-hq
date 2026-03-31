{:epupp/script-name "pez/linkedin_squirrel.cljs"
 :epupp/auto-run-match "https://www.linkedin.com/*"
 :epupp/description "LinkedIn Squirrel. Hoards posts you engage with on LinkedIn so that you can easily find them later"
 :epupp/run-at "document-idle"
 :epupp/inject ["scittle://replicant.js"]}

(ns pez.linkedin-squirrel
  (:require [clojure.edn]
            [clojure.string :as string]
            [replicant.dom :as r]))

(defonce !state
  (atom {:squirrel/posts   {}
         :squirrel/index   []
         :ui/panel-open?  false
         :ui/search-text  ""
         :ui/filter-engagement nil
         :ui/filter-media nil
         :nav/seen-urns   #{}}))

(defonce !resources (atom {}))

(defn ensure-panel-container! []
  (let [existing (:resource/panel-container @!resources)]
    (when (or (nil? existing)
              (not (.contains js/document.body existing)))
      (let [container (doto (js/document.createElement "div")
                        (set! -id "epupp-squirrel-panel-root")
                        (->> (.appendChild js/document.body)))]
        (swap! !resources assoc :resource/panel-container container)))))

(ensure-panel-container!)

(defn epupp-icon
  [& {:keys [size] :or {size 36}}]
  (let [accent-color "#FFDC73"]
    [:svg {:width size
           :height size
           :viewBox "0 0 100 100"
           :fill :none
           :xmlns "http://www.w3.org/2000/svg"}
     [:path
      {:d
       "M50 0.999996C77.062 0.999996 99 22.938 99 50C99 77.0619 77.062 99 50 99C22.9381 99 1 77.0619 1 50C1 22.938 22.9381 0.999996 50 0.999996Z"
       :fill "#4A71C4"
       :stroke accent-color
       :stroke-width "2"}]
     [:path
      {:d
       "M34.9792 36.9613L75.3818 0.999997L15.0206 37.2308L44.6048 50.8483L23.4278 84.9488L85.5 67.5L48.8818 66L55.9177 47.6053L34.9792 36.9613Z"
       :fill accent-color}]]))

(defn epupp-header [& {:keys [size title tagline]
                       :or {size 36
                            title "Epupp"
                            tagline "Live Tamper your Web"}}]
  (let [font-size (* size (/ 24 36))]
    [:div {:style {:font-size (str font-size "px")
                   :display "flex"
                   :align-items "center"
                   :gap "8px"}}
     (epupp-icon :size size)
     [:span {:style {:font-weight 500
                     :display "flex"
                     :align-items "baseline"}}
      title
      (when tagline
        [:span {:style {:font-size (str (* 0.75 font-size) "px")
                        :font-style "italic"
                        :font-weight 400
                        :margin-left "4px"}}
         tagline])]]))

(def selectors ; Selector Registry
  {:sel/feed-container    ["[data-testid='mainFeed']" "main"]
   :sel/post-container    ["[role='listitem']"]
   :sel/post-text         ["[data-testid='expandable-text-box']"]
   :sel/like-button       ["button[aria-label*='Reaction button']" "button[aria-label*='React']"]
   :sel/see-more          ["[data-testid='expandable-text-button']"]
   :sel/overflow-menu     ["button[aria-label^='Open control menu']"]
   :sel/video             ["video"]
   :sel/nav-bar           ["[data-testid='primary-nav']" "#global-nav nav"]
   :sel/nav-items-list    ["header nav ul"]
   :sel/nav-items         ["header nav ul > li"]
   :sel/user-avatar       ["header nav ul li:last-child img"]})

(def engagement-labels
  {:engaged/liked "Liked"
   :engaged/commented "Commented"
   :engaged/reposted "Reposted"
   :engaged/saved "Saved"
   :engaged/expanded "Expanded"
   :engaged/pinned "Pinned"
   :engaged/posted "Posted"
   :engaged/visited "Visited"
   :engaged/viewed "Viewed"
   :engaged/genesis "Genesis"})

(def genesis-post-urn "urn:li:activity:7434911786253832192")

(def media-labels
  {:media/text "Text"
   :media/image "Image"
   :media/video "Video"
   :media/article "Article"
   :media/document "Document"
   :media/carousel "Carousel"
   :media/poll "Poll"
   :media/celebration "Celebration"})

(def media-filter-labels
  "Filter groups for the UI. :other covers document, carousel, poll, celebration, etc."
  [[:media/text "Text"]
   [:media/image "Image"]
   [:media/video "Video"]
   [:media/article "Article"]
   [:media/other "Other"]])

(def other-media-types
  #{:media/document :media/carousel :media/poll :media/celebration})

(defn q
  "Query for first matching element using selector fallback chain."
  [context sel-key]
  (let [chain (get selectors sel-key)]
    (when chain
      (loop [[sel & more] chain
             idx 0]
        (when sel
          (let [result (try (.querySelector context sel)
                            (catch :default _e nil))]
            (if result
              result
              (recur more (inc idx)))))))))

(defn qa
  "Query for all matching elements using selector fallback chain.
   Returns a seq of elements or nil."
  [context sel-key]
  (let [chain (get selectors sel-key)]
    (when chain
      (loop [[sel & more] chain
             idx 0]
        (when sel
          (let [result (try (seq (.querySelectorAll context sel))
                            (catch :default _e nil))]
            (if (seq result)
              (do
                (when (pos? idx)
                  (js/console.warn "[epupp:squirrel] Fell to secondary selector for" (name sel-key) ":" sel))
                result)
              (recur more (inc idx)))))))))

(defn- preload-iframe-doc []
  (try
    (some-> (js/document.querySelector "iframe[src='/preload/']")
            .-contentDocument)
    (catch :default _ nil)))

(defn q-doc [sel-key]
  (or (q js/document sel-key)
      (some-> (preload-iframe-doc) (q sel-key))))

(defn qa-doc [sel-key]
  (let [main (qa js/document sel-key)
        iframe (some-> (preload-iframe-doc) (qa sel-key))]
    (seq (concat main iframe))))

;; Utility Predicates

(defn activity-urn? [urn]
  (and (string? urn)
       (or (string/starts-with? urn "urn:li:activity:")
           (string/starts-with? urn "urn:li:share:")
           (string/starts-with? urn "urn:li:synthetic:"))))

(defn- cdn-url-expired? [url]
  (when-let [m (and (string? url) (re-find #"[?&]e=(\d+)" url))]
    (< (* (js/parseInt (second m)) 1000) (.getTime (js/Date.)))))

(defn stale-images? [post]
  (or (cdn-url-expired? (:post/author-avatar-url post))
      (cdn-url-expired? (:post/media-image-url post))))

(defn- string-hash [s]
  (reduce (fn [hash ch]
            (let [h (+ (bit-shift-left hash 5) (- hash) (.charCodeAt ch 0))]
              (bit-and h 0x7fffffff)))
          0
          s))

(defn- generate-synthetic-urn [profile-url text]
  (when (and profile-url text)
    (let [slug (second (re-find #"/in/([^/?#]+)" profile-url))
          text-start (subs text 0 (min 100 (count text)))
          hash-input (str slug "|" text-start)]
      (str "urn:li:synthetic:" (string-hash hash-input)))))

(defn extract-urn-from-element
  "Extract a post URN from an element. Since LinkedIn no longer embeds URNs
   in the DOM, generates a synthetic URN from profile URL + post text."
  [el]
  (let [profile-url (some-> (or (.querySelector el "a[href*='/in/']")
                                (.querySelector el "a[href*='/company/']"))
                            (.getAttribute "href"))
        text (some-> (.querySelector el "[data-testid='expandable-text-box']")
                     .-textContent)]
    (generate-synthetic-urn profile-url text)))

(defn find-post-container
  "Find the containing post element from a target element.
   Uses role='listitem' which LinkedIn uses for feed posts."
  [el]
  (.closest el "[role='listitem']"))

(defn selector-health-check! []
  (let [results (into {}
                      (map (fn [[k _]] [k (some? (q-doc k))]))
                      selectors)
        {found true missing false} (group-by val results)]
    (js/console.log "[epupp:squirrel] Selector health check:")
    (js/console.log "  Found:" (count found) (pr-str (mapv key found)))
    (when (seq missing)
      (js/console.warn "  Missing:" (count missing) (pr-str (mapv key missing))))
    results))

;; Scraping Boundary (impure — only place touching DOM for post data)

(defn find-author-info-link
  "Find the profile link containing the actual post author's info text.
   For reshares ('X likes this'), skips the resharer's links.
   Uses control menu aria-label to identify the correct author."
  [post-el]
  (let [ctrl (.querySelector post-el "button[aria-label^='Open control menu']")
        author-name (when ctrl
                      (second (re-find #"post by (.+)$" (.getAttribute ctrl "aria-label"))))
        all-profile-links (concat
                           (array-seq (.querySelectorAll post-el "a[href*='/in/']"))
                           (array-seq (.querySelectorAll post-el "a[href*='/company/']")))
        info-link (if author-name
                    (some (fn [a]
                            (when (and (not (.querySelector a "figure"))
                                       (string/includes? (.-textContent a) author-name))
                              a))
                          all-profile-links)
                    (some (fn [a] (when-not (.querySelector a "figure") a))
                          (rest all-profile-links)))]
    {:info-link info-link
     :author-name-from-ctrl author-name}))

(defn find-author-avatar-link
  "Find the profile link containing the avatar image (figure inside).
   For reshares, matches the avatar belonging to the actual author."
  [post-el author-info-link]
  (let [all-profile-links (concat
                           (array-seq (.querySelectorAll post-el "a[href*='/in/']"))
                           (array-seq (.querySelectorAll post-el "a[href*='/company/']")))
        info-href (when author-info-link (.getAttribute author-info-link "href"))]
    (if info-href
      (some (fn [a]
              (let [href (.getAttribute a "href")]
                (when (and (.querySelector a "figure")
                           (string/starts-with? href
                                                (string/replace info-href #"/posts/$" "/")))
                  a)))
            all-profile-links)
      (some (fn [a] (when (.querySelector a "figure") a))
            all-profile-links))))

(defn extract-author-info-from-link
  "Extract author name, headline, and timestamp from the info link's structure.
   The info link contains a div with children: [name-row, headline, (cta?), timestamp]."
  [info-link author-name-from-ctrl]
  (let [info-div (when info-link (.-firstElementChild info-link))
        children (when info-div (array-seq (.-children info-div)))
        name-div (first children)
        name-text (when name-div
                    (let [fc (.-firstElementChild name-div)]
                      (when fc (string/trim (.-textContent fc)))))
        headline-div (second children)
        headline (when headline-div
                   (some-> (.querySelector headline-div "p") .-textContent string/trim))
        last-div (last children)
        timestamp (when last-div
                    (some-> (.querySelector last-div "p") .-textContent string/trim))]
    {:name (or name-text author-name-from-ctrl)
     :headline headline
     :timestamp timestamp}))

(defn scrape-post-element [post-el]
  (let [{:keys [info-link author-name-from-ctrl]} (find-author-info-link post-el)
        avatar-link (find-author-avatar-link post-el info-link)
        {:keys [name headline timestamp]} (extract-author-info-from-link info-link author-name-from-ctrl)
        avatar-img (when avatar-link (.querySelector avatar-link "img"))
        profile-url (when info-link (.getAttribute info-link "href"))
        text-box (.querySelector post-el "[data-testid='expandable-text-box']")
        post-text (when text-box (.-textContent text-box))
        feedshare-imgs (filterv (fn [img]
                                  (let [src (or (.getAttribute img "src") "")]
                                    (string/includes? src "feedshare")))
                                (.querySelectorAll post-el "img"))
        has-video (some? (.querySelector post-el "video"))
        video-el (.querySelector post-el "video")
        ext-links (.querySelectorAll post-el "a[href*='safety/go']")
        has-external-link (pos? (.-length ext-links))
        article-img (first (filterv (fn [img]
                                      (let [alt (or (.getAttribute img "alt") "")]
                                        (and (pos? (count alt))
                                             (not (string/starts-with? alt "View ")))))
                                    feedshare-imgs))
        has-article (and has-external-link (some? article-img))
        has-image (and (pos? (count feedshare-imgs)) (not has-article))
        has-iframe (some? (.querySelector post-el "iframe"))]
    {:raw/urn (generate-synthetic-urn profile-url post-text)
     :raw/author-name name
     :raw/author-headline headline
     :raw/author-avatar-url (when avatar-img (.getAttribute avatar-img "src"))
     :raw/author-profile-url profile-url
     :raw/text post-text
     :raw/timestamp-text timestamp
     :raw/has-article? has-article
     :raw/article-title (when article-img (.getAttribute article-img "alt"))
     :raw/article-subtitle nil
     :raw/article-url (when has-article
                        (some-> ext-links first (.getAttribute "href")))
     :raw/article-image-url (when article-img (.getAttribute article-img "src"))
     :raw/has-video? has-video
     :raw/video-poster-url (when video-el
                             (or (.getAttribute video-el "poster")
                                 (some-> (.-parentElement video-el) (.querySelector "img") (.getAttribute "src"))))
     :raw/has-document? has-iframe
     :raw/document-title (some-> (.querySelector post-el "iframe") (.getAttribute "title"))
     :raw/has-carousel? false
     :raw/carousel-image-url nil
     :raw/has-poll? false
     :raw/has-celebration? false
     :raw/celebration-image-url nil
     :raw/has-image? has-image
     :raw/image-url (when has-image
                      (some-> (first feedshare-imgs) (.getAttribute "src")))
     :raw/has-reshare? false
     :raw/reshare-post nil}))

;; Pure Transforms (testable without DOM)

(defn text-preview [raw-text]
  (when raw-text
    (let [trimmed (string/trim raw-text)]
      (if (> (count trimmed) 500)
        (str (subs trimmed 0 500) "\u2026")
        trimmed))))

(defn detect-media-type [{:keys [raw/has-article? raw/has-video? raw/has-document?
                                 raw/has-carousel? raw/has-poll? raw/has-celebration?
                                 raw/has-image?]}]
  (cond
    has-article?     :media/article
    has-video?       :media/video
    has-document?    :media/document
    has-carousel?    :media/carousel
    has-poll?        :media/poll
    has-celebration? :media/celebration
    has-image?       :media/image
    :else            :media/text))

(defn raw->post-snapshot [raw-data now]
  (let [media-type (detect-media-type raw-data)]
    (cond-> {:post/urn (:raw/urn raw-data)
             :post/first-seen now
             :post/last-engaged now
             :post/author-name (:raw/author-name raw-data)
             :post/author-headline (:raw/author-headline raw-data)
             :post/author-avatar-url (:raw/author-avatar-url raw-data)
             :post/author-profile-url (:raw/author-profile-url raw-data)
             :post/text-preview (text-preview (:raw/text raw-data))
             :post/media-type media-type
             :post/media-image-url (or (:raw/video-poster-url raw-data)
                                       (:raw/image-url raw-data)
                                       (:raw/carousel-image-url raw-data)
                                       (:raw/celebration-image-url raw-data))
             :post/reshare? (:raw/has-reshare? raw-data)
             :post/engagements #{}
             :post/pinned? false}
      (= media-type :media/document)
      (assoc :post/document-title (:raw/document-title raw-data))
      (= media-type :media/article)
      (assoc :post/article-title (:raw/article-title raw-data)
             :post/article-subtitle (:raw/article-subtitle raw-data)
             :post/article-url (:raw/article-url raw-data)
             :post/article-image-url (:raw/article-image-url raw-data))
      (:raw/reshare-post raw-data)
      (assoc :post/reshared-post (raw->post-snapshot (:raw/reshare-post raw-data) now)))))

(defn extract-profile-slug [url]
  (some-> url
          (as-> u (second (re-find #"/in/([^/?#]+)" u)))
          string/lower-case))

(defn own-post? [current-user-slug raw-data]
  (let [author-slug (extract-profile-slug (:raw/author-profile-url raw-data))]
    (and (some? current-user-slug)
         (some? author-slug)
         (= current-user-slug author-slug))))

(defn find-post-urn [el]
  (when-let [post-el (find-post-container el)]
    (let [raw (scrape-post-element post-el)]
      (when (activity-urn? (:raw/urn raw))
        (:raw/urn raw)))))

(defonce native-storage-fns
  (let [iframe (js/document.createElement "iframe")
        _ (set! (.. iframe -style -display) "none")
        _ (.appendChild js/document.body iframe)
        clean-window (.-contentWindow iframe)
        proto (.-prototype (.-Storage clean-window))
        set-item (.-setItem proto)
        get-item (.-getItem proto)
        remove-item (.-removeItem proto)]
    {:set-item set-item
     :get-item get-item
     :remove-item remove-item}))

(def storage-key "epupp:linkedin-squirrel/posts")
(def post-cap 500)
(def prune-batch 50)

(defn storage-set! [k v]
  (.call (:set-item native-storage-fns) js/localStorage k v))

(defn storage-get [k]
  (.call (:get-item native-storage-fns) js/localStorage k))

(defn storage-remove! [k]
  (.call (:remove-item native-storage-fns) js/localStorage k))

(defn find-existing-urn
  "Find the URN of an already-hoarded post that matches the given snapshot
   by content identity (author slug + text-preview). Uses extract-profile-slug
   for cross-era matching (old URLs had query params, new ones don't)."
  [posts urn snapshot]
  (when-not (contains? posts urn)
    (let [author-slug (extract-profile-slug (:post/author-profile-url snapshot))
          text (:post/text-preview snapshot)]
      (when (and author-slug text)
        (some (fn [[existing-urn post]]
                (when (and (= author-slug (extract-profile-slug (:post/author-profile-url post)))
                           (= text (:post/text-preview post)))
                  existing-urn))
              posts)))))

(defn hoard-post
  "Add or update a post in state. Merges engagement and preserves pin.
   Deduplicates by content identity: if a post with the same author+text
   already exists under a different URN, merges into the existing entry.
   Heals image URLs from fresh snapshot on every encounter."
  [state urn snapshot engagement-type now]
  (let [resolved-urn (or (find-existing-urn (:squirrel/posts state) urn snapshot)
                         urn)
        existing (get-in state [:squirrel/posts resolved-urn])
        fresh-images (select-keys snapshot [:post/author-avatar-url
                                            :post/author-profile-url
                                            :post/media-image-url])
        merged (if existing
                 (-> existing
                     (merge fresh-images)
                     (update :post/engagements (fnil conj #{}) engagement-type)
                     (assoc :post/last-engaged now))
                 (-> snapshot
                     (assoc :post/engagements #{engagement-type})
                     (assoc :post/last-engaged now)))]
    (-> state
        (assoc-in [:squirrel/posts resolved-urn] merged)
        (update :squirrel/index
                (fn [idx]
                  (if (some #{resolved-urn} idx)
                    idx
                    (conj (or idx []) resolved-urn)))))))

(defn toggle-pin [state urn]
  (update-in state [:squirrel/posts urn :post/pinned?] not))

(defn remove-post [state urn]
  (-> state
      (update :squirrel/posts dissoc urn)
      (update :squirrel/index #(vec (remove #{urn} %)))))

(defn hoard-own-post
  "Hoard a post authored by the current user.
   New post: hoarded with :engaged/posted, timestamps set to now.
   Already hoarded without :engaged/posted: heals images and adds engagement,
   doesn't update last-engaged.
   Already has :engaged/posted: heals image URLs and keeps it otherwise unchanged.
   Deduplicates by content identity like hoard-post."
  [state urn snapshot]
  (let [resolved-urn (or (find-existing-urn (:squirrel/posts state) urn snapshot)
                         urn)
        existing (get-in state [:squirrel/posts resolved-urn])
        fresh-images (select-keys snapshot [:post/author-avatar-url
                                            :post/author-profile-url
                                            :post/media-image-url])]
    (cond
      (contains? (:post/engagements existing) :engaged/posted)
      (update-in state [:squirrel/posts resolved-urn] merge fresh-images)

      existing
      (-> state
          (update-in [:squirrel/posts resolved-urn] merge fresh-images)
          (update-in [:squirrel/posts resolved-urn :post/engagements] conj :engaged/posted))

      :else
      (-> state
          (assoc-in [:squirrel/posts resolved-urn]
                    (-> snapshot
                        (assoc :post/engagements #{:engaged/posted})))
          (update :squirrel/index conj resolved-urn)))))

(defn prune-posts
  "Remove oldest unpinned posts when over capacity."
  [state]
  (let [posts (:squirrel/posts state)
        index (:squirrel/index state)]
    (if (<= (count posts) post-cap)
      state
      (let [unpinned-oldest (->> index
                                 (filter #(not (get-in posts [% :post/pinned?])))
                                 (sort-by #(get-in posts [% :post/first-seen]))
                                 (take prune-batch))
            remove-set (set unpinned-oldest)]
        (-> state
            (update :squirrel/posts #(apply dissoc % unpinned-oldest))
            (update :squirrel/index #(vec (remove remove-set %))))))))

(defn make-debounced [delay-ms f]
  (let [!timeout (atom nil)]
    (fn [& args]
      (when-let [t @!timeout]
        (js/clearTimeout t))
      (reset! !timeout
              (js/setTimeout #(apply f args) delay-ms)))))

(defn- attach-listener!
  [target event resource-key handler-fn opts]
  (let [{:keys [capture?] :or {capture? false}} opts]
    (when-let [old (resource-key @!resources)]
      (.removeEventListener target event old capture?))
    (swap! !resources assoc resource-key handler-fn)
    (.addEventListener target event handler-fn capture?)))

(defn- detach-listener!
  [target event resource-key opts]
  (let [{:keys [capture?] :or {capture? false}} opts]
    (when-let [handler (resource-key @!resources)]
      (.removeEventListener target event handler capture?)
      (swap! !resources assoc resource-key nil))))

(defn merge-post
  "Merge two versions of the same post. Unions engagements, keeps max
   last-engaged, preserves pinned if either is pinned."
  [a b]
  (-> (if (pos? (compare (:post/last-engaged a) (:post/last-engaged b))) a b)
      (update :post/engagements into (:post/engagements a))
      (update :post/engagements into (:post/engagements b))
      (assoc :post/pinned? (or (:post/pinned? a) (:post/pinned? b)))))

(defn upsert-posts
  "Upsert in-memory posts into storage state. Additive only: never removes
   posts from storage, only adds new ones or merges existing ones."
  [storage-state in-memory-state]
  (let [storage-posts (:squirrel/posts storage-state)
        in-mem-posts (:squirrel/posts in-memory-state)
        updated-posts (reduce-kv
                       (fn [acc urn post]
                         (if-let [existing (get acc urn)]
                           (assoc acc urn (merge-post existing post))
                           (assoc acc urn post)))
                       storage-posts
                       in-mem-posts)
        storage-index (or (:squirrel/index storage-state) [])
        index-set (set storage-index)
        new-urns (remove index-set (keys in-mem-posts))]
    {:squirrel/posts updated-posts
     :squirrel/index (into storage-index new-urns)}))

(defn read-persisted-state []
  (when-let [raw (storage-get storage-key)]
    (try
      (let [{:keys [posts index]} (clojure.edn/read-string raw)]
        {:squirrel/posts (or posts {})
         :squirrel/index (or index [])})
      (catch :default _ nil))))

(defn write-state!
  "Low-level: write the given posts state to storage and sync back to atom."
  [{:keys [squirrel/posts squirrel/index] :as state}]
  (storage-set! storage-key (pr-str {:posts posts :index index}))
  (swap! !state assoc :squirrel/posts posts :squirrel/index index)
  state)

(defn storage-transact!
  "Read storage, apply f to it, write back, sync atom. For immediate
   operations (delete, pin) that must not be lost to debouncing."
  [f & args]
  (let [persisted (or (read-persisted-state)
                      {:squirrel/posts {} :squirrel/index []})
        updated (apply f persisted args)]
    (write-state! updated)))

(defn ensure-genesis-post!
  "Ensure the genesis post exists in the hoard with :engaged/genesis."
  []
  (storage-transact!
   (fn [state]
     (let [urn genesis-post-urn
           existing (get-in state [:squirrel/posts urn])]
       (if existing
         (-> state
             (update-in [:squirrel/posts urn :post/engagements] conj :engaged/genesis)
             (assoc-in [:squirrel/posts urn :post/author-avatar-url]
                       "https://media.licdn.com/dms/image/v2/D4D03AQGtmEWdnxAxDQ/profile-displayphoto-shrink_100_100/profile-displayphoto-shrink_100_100/0/1666595543864?e=1776297600&v=beta&t=_puNwcb-U5f0TgNpvvZZrsUuf--yhxlCK36gBNjNH5o")
             (assoc-in [:squirrel/posts urn :post/author-profile-url]
                       "https://www.linkedin.com/in/cospaia/")
             (assoc-in [:squirrel/posts urn :post/media-image-url]
                       "https://media.licdn.com/dms/image/v2/D4D05AQHDh2A2GSu1_g/feedshare-thumbnail_720_1280/B4DZy4A2H.GUA8-/0/1772613758781?e=1775574000&v=beta&t=g3bVbs1xJctRDyFI69d8JzjQyf8ta-NwazXgzr-ctt8"))
         (let [now (.toISOString (js/Date.))]
           (-> state
               (assoc-in [:squirrel/posts urn]
                         {:post/urn urn
                          :post/author-name "Peter (PEZ) Strömberg"
                          :post/author-headline "Clojurian Contractor, Hacking on Calva. I just want to code, dammit! Haha"
                          :post/author-avatar-url "https://media.licdn.com/dms/image/v2/D4D03AQGtmEWdnxAxDQ/profile-displayphoto-shrink_100_100/profile-displayphoto-shrink_100_100/0/1666595543864?e=1776297600&v=beta&t=_puNwcb-U5f0TgNpvvZZrsUuf--yhxlCK36gBNjNH5o"
                          :post/author-profile-url "https://www.linkedin.com/in/cospaia/"
                          :post/text-preview "I made a browser userscript I call LinkedIn Squirrel, which hoards posts I engage with and gives me a UI for quickly finding them. The Squirrel also helps recover posts that vanish under my nose while I am reading them in the feed.You can use Squirrel too. You need Epupp, an extension for Chrome and Firefox, available at the extension stores. (I made Epupp too). And you need the Squirrel script:https://lnkd.in/d2swsZkf(Open/reload the script page with Epupp installed.)Please let me know if your \u2026"
                          :post/media-type :media/video
                          :post/media-image-url "https://media.licdn.com/dms/image/v2/D4D05AQHDh2A2GSu1_g/feedshare-thumbnail_720_1280/B4DZy4A2H.GUA8-/0/1772613758781?e=1775574000&v=beta&t=g3bVbs1xJctRDyFI69d8JzjQyf8ta-NwazXgzr-ctt8"
                          :post/reshare? false
                          :post/pinned? false
                          :post/engagements #{:engaged/genesis}
                          :post/first-seen now
                          :post/last-engaged now})
               (update :squirrel/index
                       (fn [idx] (conj (or idx []) urn))))))))))

(defn save-state!
  "Debounced save: upserts in-memory posts into storage. Additive only —
   never removes posts from storage. Deletions go through storage-transact!."
  []
  (swap! !state prune-posts)
  (let [in-memory {:squirrel/posts (:squirrel/posts @!state)
                   :squirrel/index (:squirrel/index @!state)}
        persisted (or (read-persisted-state)
                      {:squirrel/posts {} :squirrel/index []})
        merged (upsert-posts persisted in-memory)]
    (write-state! merged)
    (js/console.log "[epupp:squirrel] Saved" (count (:squirrel/posts merged)) "posts")))

(defn load-state! []
  (let [from-new (storage-get storage-key)
        from-old (when-not from-new (storage-get "epupp:linkedin-squirrel"))
        raw (or from-new from-old)]
    (when raw
      (try
        (let [{:keys [posts index]} (clojure.edn/read-string raw)]
          (swap! !state merge
                 {:squirrel/posts (or posts {})
                  :squirrel/index (or index [])})
          (when from-old
            (save-state!)
            (storage-remove! "epupp:linkedin-squirrel")
            (js/console.log "[epupp:squirrel] Migrated from old storage key"))
          (js/console.log "[epupp:squirrel] Loaded" (count posts) "posts"))
        (catch :default e
          (js/console.error "[epupp:squirrel] Failed to load state:" e))))))

(def schedule-save! (make-debounced 3000 save-state!))

(defn extract-click-context [target]
  (let [closest-btn (when (not= (.. target -tagName toLowerCase) "button")
                      (.closest target "button"))
        resolved (or closest-btn target)]
    {:btn-aria (or (some-> resolved (.getAttribute "aria-label")) "")
     :text (string/trim (or (.-textContent resolved) ""))}))

(def click-patterns
  [{:source :btn-aria :pattern #"(?i)react"    :engagement :engaged/liked}
   {:source :btn-aria :pattern #"(?i)comment"  :engagement :engaged/commented}
   {:source :text     :pattern #"(?i)repost"   :engagement :engaged/reposted}
   {:source :text     :pattern #"(?i)\bsave\b" :engagement :engaged/saved}
   {:source :text     :pattern #"(?i)more"     :engagement :engaged/expanded}
   {:source :text     :pattern #"(?i)view larger image" :engagement :engaged/viewed}
   {:source :btn-aria :pattern #"(?i)navigate to" :engagement :engaged/viewed}])

(defn interpret-click [click-context]
  (some (fn [{:keys [source pattern engagement]}]
          (when (re-find pattern (get click-context source ""))
            engagement))
        click-patterns))

(defn handle-engagement! [e]
  (try
    (let [target (.-target e)]
      (when-let [engagement (interpret-click (extract-click-context target))]
        (when-let [urn (find-post-urn target)]
          (let [post-el (find-post-container target)
                now (.toISOString (js/Date.))
                raw (scrape-post-element post-el)
                snapshot (raw->post-snapshot raw now)]
            (swap! !state hoard-post urn snapshot engagement now)
            (schedule-save!)
            (js/console.log "[epupp:squirrel] Engagement:" (name engagement) urn)))))
    (catch :default err
      (js/console.error "[epupp:squirrel] Engagement handler error:" err))))

(defn attach-engagement-listener! []
  (attach-listener! js/document.body "click" :resource/engagement-handler handle-engagement! {:capture? true}))

(defn detach-engagement-listener! []
  (detach-listener! js/document.body "click" :resource/engagement-handler {:capture? true}))

(defn handle-comment-input! [e]
  (try
    (let [target (.-target e)]
      (when (.closest target ".comments-comment-box, .comments-comment-texteditor")
        (when-let [post-el (find-post-container target)]
          (let [raw (scrape-post-element post-el)
                urn (:raw/urn raw)]
            (when (and (activity-urn? urn)
                       (not (contains?
                             (get-in @!state [:squirrel/posts urn :post/engagements])
                             :engaged/commented)))
              (let [now (.toISOString (js/Date.))
                    snapshot (raw->post-snapshot raw now)]
                (swap! !state hoard-post urn snapshot :engaged/commented now)
                (schedule-save!)
                (js/console.log "[epupp:squirrel] Engagement: commented (input)" urn)))))))
    (catch :default err
      (js/console.error "[epupp:squirrel] Comment input handler error:" err))))

(defn attach-comment-input-listener! []
  (attach-listener! js/document.body "input" :resource/comment-input-handler
                    handle-comment-input! {:capture? true}))

(defn detach-comment-input-listener! []
  (detach-listener! js/document.body "input" :resource/comment-input-handler
                    {:capture? true}))

(defn- attach-iframe-engagement-listener! [iframe-body]
  (when-let [old (:resource/iframe-engagement-handler @!resources)]
    (.removeEventListener (.-target old) "click" (.-handler old) true))
  (let [handler handle-engagement!]
    (.addEventListener iframe-body "click" handler true)
    (swap! !resources assoc :resource/iframe-engagement-handler
           #js {:target iframe-body :handler handler})))

(defn- detach-iframe-engagement-listener! []
  (when-let [old (:resource/iframe-engagement-handler @!resources)]
    (.removeEventListener (.-target old) "click" (.-handler old) true)
    (swap! !resources assoc :resource/iframe-engagement-handler nil)))

(defn initials [author-name]
  (when author-name
    (->> (string/split author-name #" ")
         (take 2)
         (map #(subs % 0 1))
         (string/join ""))))

(defn extract-domain [url]
  (when (and url (string? url))
    (try
      (.-hostname (js/URL. url))
      (catch :default _ nil))))

(defn media-thumbnail [{:keys [post/media-type post/media-image-url post/document-title]}]
  (case media-type
    :media/image
    (when media-image-url
      [:img {:src media-image-url
             :style {:width "100%" :border-radius "6px"
                     :margin-bottom "6px"}}])
    :media/video
    (when media-image-url
      [:div {:style {:position "relative" :width "100%"
                     :border-radius "6px" :margin-bottom "6px"}}
       [:img {:src media-image-url
              :style {:width "100%" :border-radius "6px"}}]
       [:div {:style {:position "absolute" :bottom "12px" :right "6px"
                      :background "rgba(0,0,0,0.6)" :color "white"
                      :padding "2px 6px" :border-radius "4px" :font-size "10px"}}
        "\u25B6 Video"]])
    :media/document
    [:div {:style {:width "100%" :padding "8px 12px" :border-radius "6px"
                   :background "#f0f0f0" :display "flex" :align-items "center"
                   :gap "8px" :margin-bottom "6px"}}
     [:span {:style {:font-size "22px" :flex-shrink "0"}} "\uD83D\uDCC4"]
     [:span {:style {:font-size "12px" :color "#444" :font-weight "600"
                     :overflow "hidden" :text-overflow "ellipsis"
                     :display "-webkit-box" :-webkit-line-clamp "2"
                     :-webkit-box-orient "vertical"}}
      (or document-title "Document")]]
    :media/carousel
    (if media-image-url
      [:div {:style {:position "relative" :width "100%"
                     :border-radius "6px" :margin-bottom "6px"}}
       [:img {:src media-image-url
              :style {:width "100%" :border-radius "6px"}}]
       [:div {:style {:position "absolute" :bottom "4px" :right "4px"
                      :background "rgba(0,0,0,0.6)" :color "white"
                      :padding "2px 6px" :border-radius "4px" :font-size "10px"}}
        "\uD83C\uDFA0 Carousel"]]
      [:div {:style {:width "100%" :height "48px" :border-radius "6px"
                     :background "#f0f0f0" :display "flex" :align-items "center"
                     :justify-content "center" :margin-bottom "6px"}}
       [:span {:style {:font-size "18px" :color "#666"}} "\uD83C\uDFA0 Carousel"]])
    :media/celebration
    (if media-image-url
      [:img {:src media-image-url
             :style {:width "100%" :border-radius "6px"
                     :margin-bottom "6px"}}]
      [:div {:style {:width "100%" :height "48px" :border-radius "6px"
                     :background "#f0f0f0" :display "flex" :align-items "center"
                     :justify-content "center" :margin-bottom "6px"}}
       [:span {:style {:font-size "18px" :color "#666"}} "\uD83C\uDF89 Celebration"]])
    :media/poll
    [:div {:style {:width "100%" :height "48px" :border-radius "6px"
                   :background "#f0f0f0" :display "flex" :align-items "center"
                   :justify-content "center" :margin-bottom "6px"}}
     [:span {:style {:font-size "18px" :color "#666"}} "\uD83D\uDCCA Poll"]]
    nil))

(defn article-mini-card [{:keys [post/article-title post/article-url
                                 post/article-image-url post/media-image-url]}]
  (let [domain (extract-domain article-url)
        img-url (or article-image-url media-image-url)]
    [:div {:style {:display "flex" :gap "8px" :padding "8px"
                   :background "#f8f9fa" :border-radius "6px"
                   :border "1px solid #e8e8e8" :margin-bottom "6px"}}
     (when img-url
       [:img {:src img-url
              :style {:width "48px" :border-radius "4px"
                      :flex-shrink "0"}}])
     [:div {:style {:flex "1" :min-width "0"}}
      (when article-title
        [:div {:style {:font-size "12px" :font-weight "600" :color "#333"
                       :white-space "nowrap" :overflow "hidden"
                       :text-overflow "ellipsis"}}
         article-title])
      (when domain
        [:div {:style {:font-size "10px" :color "#999" :margin-top "2px"}}
         domain])]]))

(defn nav-button-view [{:keys [post-count open?]}]
  [:li.global-nav__primary-item {:id "epupp-squirrel-nav-btn"
                                 :style {:margin-left "1rem"}}
   [:button {:type "button"
             :style {:background "none" :border "none" :cursor "pointer"
                     :display "flex" :flex-direction "column" :align-items "center"
                     :padding "0" :width "48px" :height "52px" :justify-content "center"
                     :color (if open? "#0a66c2" "rgba(0,0,0,0.6)")}
             :on {:click (fn [e] (.stopPropagation e)
                           (swap! !state update :ui/panel-open? not))}}
    [:span {:style {:position "relative" :display "flex" :align-items "center"
                    :justify-content "center"}
            :title (str post-count " posts hoarded")}
     [:svg {:viewBox "0 0 24 24" :width "24" :height "24" :fill "currentColor"
            :xmlns "http://www.w3.org/2000/svg"}
      [:path {:d "M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"}]]]
    [:span {:style {:font-size "12px" :color "inherit" :line-height "1"
                    :display "inline-flex" :align-items "center" :gap "2px"}}
     "Squirrel"
     [:span {:style {:border-left "5px solid transparent"
                     :border-right "5px solid transparent"
                     :border-top "6px solid currentColor"}}]]]])

(defn- find-me-nav-item [nav-list]
  (some (fn [item]
          (when (or (q item :sel/user-avatar)
                    (re-find #"(?i)^\s*Me\s*$" (.-textContent item)))
            item))
        (qa nav-list :sel/nav-items)))

(defn ensure-nav-button! []
  (when-let [nav-list (q-doc :sel/nav-items-list)]
    (let [owner-doc (.-ownerDocument nav-list)
          mount-el (or (.getElementById owner-doc "epupp-squirrel-nav-mount")
                       (let [el (.createElement owner-doc "div")
                             me-item (find-me-nav-item nav-list)]
                         (set! (.-id el) "epupp-squirrel-nav-mount")
                         (set! (.. el -style -display) "contents")
                         (if me-item
                           (.insertBefore nav-list el me-item)
                           (.appendChild nav-list el))
                         (swap! !resources assoc :resource/nav-mount el)
                         el))]
      (r/render mount-el
                (nav-button-view {:post-count (count (:squirrel/posts @!state))
                                  :open? (:ui/panel-open? @!state)})))))

(defn inject-pin-button! [post-el urn]
  (when-not (.querySelector post-el "[data-epupp-pin]")
    (let [overflow-btn (q post-el :sel/overflow-menu)
          target-container (when overflow-btn (.-parentElement overflow-btn))
          owner-doc (.-ownerDocument post-el)
          btn (.createElement owner-doc "button")
          pinned? (get-in @!state [:squirrel/posts urn :post/pinned?])]
      (when target-container
        (let [sibling-margin (.. (js/getComputedStyle overflow-btn) -marginTop)]
          (.setAttribute btn "data-epupp-pin" urn)
          (set! (.. btn -style -cssText)
                (str "background: none; border: none; cursor: pointer; padding: 0; font-size: 16px; line-height: 1; color: #666; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; width: 32px; height: 32px; margin-top: " sibling-margin ";"))
          (set! (.-textContent btn) (if pinned? "\u2605" "\u2606")))
        (when pinned? (set! (.. btn -style -color) "#f59e0b"))
        (.addEventListener btn "mouseenter"
                           (fn [_] (set! (.. btn -style -background) "rgba(0,0,0,0.08)")))
        (.addEventListener btn "mouseleave"
                           (fn [_] (set! (.. btn -style -background) "none")))
        (.addEventListener btn "click"
                           (fn [e]
                             (.stopPropagation e)
                             (.preventDefault e)
                             (let [now (.toISOString (js/Date.))
                                   raw (scrape-post-element post-el)
                                   snapshot (raw->post-snapshot raw now)]
                               (storage-transact! (fn [s]
                                                    (let [s (if (get-in s [:squirrel/posts urn])
                                                              s
                                                              (hoard-post s urn snapshot :engaged/pinned now))]
                                                      (toggle-pin s urn))))
                               (let [now-pinned? (get-in @!state [:squirrel/posts urn :post/pinned?])]
                                 (set! (.-textContent btn) (if now-pinned? "\u2605" "\u2606"))
                                 (set! (.. btn -style -color) (if now-pinned? "#f59e0b" "#666"))))))
        (.insertBefore target-container btn overflow-btn)))))

(defn detect-current-user-slug! []
  (when-not (:nav/current-user-slug @!state)
    (let [me-img (some-> (js/document.querySelector "header nav ul li:last-child img")
                         (.getAttribute "src"))
          slug (when me-img
                 (some (fn [post-el]
                         (let [{:keys [info-link]} (find-author-info-link post-el)
                               avatar-link (find-author-avatar-link post-el info-link)
                               avatar-img (when avatar-link (.querySelector avatar-link "img"))
                               avatar-src (when avatar-img (.getAttribute avatar-img "src"))]
                           (when (and avatar-src (= avatar-src me-img))
                             (extract-profile-slug
                              (when info-link (.getAttribute info-link "href"))))))
                       (qa-doc :sel/post-container)))]
      (when slug
        (swap! !state assoc :nav/current-user-slug slug)
        (js/console.log "[epupp:squirrel] Current user detected:" slug)
        slug))))

(defn scan-post! [post-el]
  (let [raw (scrape-post-element post-el)]
    (when (activity-urn? (:raw/urn raw))
      (let [urn (:raw/urn raw)]
        ;; Always try pin injection (idempotent — checks for existing pin)
        (inject-pin-button! post-el urn)
        (when-not ((:nav/seen-urns @!state) urn)
          (swap! !state update :nav/seen-urns conj urn)
          (when-let [current-user-slug (:nav/current-user-slug @!state)]
            (when (own-post? current-user-slug raw)
              (let [now (.toISOString (js/Date.))
                    snapshot (raw->post-snapshot raw now)]
                (swap! !state hoard-own-post urn snapshot)
                (schedule-save!)
                (js/console.log "[epupp:squirrel] Own post detected:" urn)))))))))

(defn scan-visible-posts! []
  (doseq [post-el (qa-doc :sel/post-container)]
    (scan-post! post-el)))

(def single-post-url-pattern #"/feed/update/(urn:li:activity:\d+)")

(defn on-feed-page? []
  (not (re-find single-post-url-pattern (.-href js/window.location))))

(defn hoard-visited-post!
  "When on a single-post page, hoard that post with :engaged/visited."
  []
  (when-let [match (re-find single-post-url-pattern (.-href js/window.location))]
    (let [urn (second match)]
      (when-let [post-el (first (qa-doc :sel/post-container))]
        (let [raw (scrape-post-element post-el)]
          (when (activity-urn? (:raw/urn raw))
            (let [now (.toISOString (js/Date.))
                  snapshot (raw->post-snapshot raw now)]
              (swap! !state hoard-post urn snapshot :engaged/visited now)
              (schedule-save!)
              (js/console.log "[epupp:squirrel] Visited post:" urn))))))))

(declare process-mutations!)

;; ── Viewport Buffer (feed refresh protection) ────────────────────

(def viewport-buffer-size 7)

(defonce !viewport-buffer (atom {:seen [] :snapshots {}}))

(defn buffer-viewport-post! [urn post-el]
  (let [raw (scrape-post-element post-el)
        snapshot (raw->post-snapshot raw (.toISOString (js/Date.)))]
    (swap! !viewport-buffer
           (fn [{:keys [seen snapshots]}]
             (let [new-seen (if (some #{urn} seen)
                              seen
                              (let [appended (conj seen urn)]
                                (if (> (count appended) viewport-buffer-size)
                                  (subvec appended (- (count appended) viewport-buffer-size))
                                  appended)))
                   kept-urns (set new-seen)]
               {:seen new-seen
                :snapshots (-> (select-keys (or snapshots {}) kept-urns)
                               (assoc urn snapshot))})))))

(defn reset-viewport-buffer! []
  (reset! !viewport-buffer {:seen [] :snapshots {}}))

(defn buffer-visible-viewport-posts! []
  (when (on-feed-page?)
    (doseq [post-el (qa-doc :sel/post-container)]
      (let [rect (.getBoundingClientRect post-el)]
        (when (and (<= (.-top rect) (.-innerHeight js/window))
                   (>= (.-bottom rect) 0))
          (when-let [urn (extract-urn-from-element post-el)]
            (when (activity-urn? urn)
              (buffer-viewport-post! urn post-el))))))))

(defn get-feed-urns []
  (set (keep (fn [post-el]
               (let [raw (scrape-post-element post-el)]
                 (:raw/urn raw)))
             (qa-doc :sel/post-container))))

(defn detect-feed-refresh []
  (let [{:keys [seen]} @!viewport-buffer]
    (when (>= (count seen) 3)
      (let [dom-urns (get-feed-urns)
            vanished (filterv (complement dom-urns) seen)]
        (when (seq vanished)
          vanished)))))

(defn topmost-viewport-post-el []
  (some (fn [post]
          (let [rect (.getBoundingClientRect post)]
            (when (and (>= (.-bottom rect) 0)
                       (<= (.-top rect) (.-innerHeight js/window)))
              post)))
        (qa-doc :sel/post-container)))

(defn post-card-body
  "Shared hiccup for post cards. Returns a list (Replicant fragment).
   `actions` is hiccup for the top-right area of the author row."
  [{:post/keys [author-name author-avatar-url author-headline
                text-preview media-type] :as post}
   actions]
  (list
   [:div {:style {:display "flex" :align-items "flex-start" :gap "8px" :margin-bottom "6px"}}
    (if author-avatar-url
      [:img {:src author-avatar-url
             :style {:width "32px" :height "32px" :border-radius "50%"
                     :object-fit "cover"}}]
      [:div {:style {:width "32px" :height "32px" :border-radius "50%"
                     :background "#0a66c2" :color "white" :display "flex"
                     :align-items "center" :justify-content "center"
                     :font-size "12px" :font-weight "bold"}}
       (initials author-name)])
    [:div {:style {:flex "1" :min-width "0"}}
     [:div {:style {:font-weight "600" :font-size "13px" :white-space "nowrap"
                    :overflow "hidden" :text-overflow "ellipsis"}}
      (or author-name "Unknown")]
     [:div {:style {:font-size "11px" :color "#666" :white-space "nowrap"
                    :overflow "hidden" :text-overflow "ellipsis"}}
      (or author-headline "")]]
    actions]
   (when text-preview
     [:div {:style {:font-size "12px" :color "#333" :margin-bottom "6px"
                    :display "-webkit-box" :-webkit-line-clamp "2"
                    :-webkit-box-orient "vertical" :overflow "hidden"}}
      text-preview])
   (if-let [{reshare-author :post/author-name
             reshare-avatar :post/author-avatar-url
             reshare-headline :post/author-headline
             reshare-text :post/text-preview
             reshare-media-type :post/media-type
             :as reshared-post} (:post/reshared-post post)]
     [:div {:style {:border "1px solid #e0e0e0" :border-radius "6px"
                    :padding "8px" :margin-top "4px" :background "#f8f9fa"}}
      [:div {:style {:display "flex" :align-items "center" :gap "6px" :margin-bottom "4px"}}
       (when reshare-avatar
         [:img {:src reshare-avatar
                :style {:width "20px" :height "20px" :border-radius "50%"
                        :object-fit "cover"}}])
       [:div {:style {:flex "1" :min-width "0"}}
        [:span {:style {:font-weight "600" :font-size "11px"}} reshare-author]
        (when reshare-headline
          [:span {:style {:font-size "10px" :color "#666" :margin-left "4px"}}
           reshare-headline])]]
      (when reshare-text
        [:div {:style {:font-size "11px" :color "#444" :margin-bottom "4px"
                       :display "-webkit-box" :-webkit-line-clamp "3"
                       :-webkit-box-orient "vertical" :overflow "hidden"}}
         reshare-text])
      (media-thumbnail reshared-post)
      (when (= reshare-media-type :media/article)
        (article-mini-card reshared-post))]
     (list
       (media-thumbnail post)
       (when (= media-type :media/article)
         (article-mini-card post))))))

(defn vanished-post-card [{:post/keys [urn media-type] :as post}
                          {:keys [on-pin]}]
  (let [pinned? (get-in @!state [:squirrel/posts urn :post/pinned?])]
    [:div {:replicant/key urn
           :style {:padding "12px" :border-bottom "1px solid #e0e0e0"
                   :border-left "3px solid #f59e0b"
                   :background (if pinned? "#fffde7" "white")}}
     (post-card-body post
       [:div {:style {:display "flex" :align-items "center" :gap "4px"
                      :flex-shrink "0" :margin-top "2px"}}
        [:button {:style {:background "none" :border "none" :cursor "pointer"
                          :font-size "16px" :padding "4px" :line-height "1"
                          :color (if pinned? "#f59e0b" "#666")}
                  :title (if pinned? "Unpin" "Pin to hoard")
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (on-pin urn post))}}
         (if pinned? "\u2605" "\u2606")]
        [:button {:style {:background "none" :border "none" :cursor "pointer"
                          :font-size "13px" :padding "4px" :line-height "1"
                          :color "#0a66c2"}
                  :title "Open post"
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (js/window.open (str "https://www.linkedin.com/feed/update/" urn "/") "_blank"))}}
         "\u2197"]])
     [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap"}}
      (when media-type
        [:span {:style {:background "#e3f2fd" :color "#1565c0" :padding "2px 6px"
                        :border-radius "4px" :font-size "10px"}}
         (get media-labels media-type "?")])]]))

(defn render-vanished-cards! [mount snapshots]
  (let [on-pin (fn [urn snapshot]
                 (let [now (.toISOString (js/Date.))]
                   (storage-transact! (fn [s]
                                       (let [s (if (get-in s [:squirrel/posts urn])
                                                 s
                                                 (hoard-post s urn snapshot :engaged/pinned now))]
                                         (toggle-pin s urn))))
                   (render-vanished-cards! mount snapshots)))
        on-dismiss (fn [_]
                     (.removeChild (.-parentElement mount) mount)
                     (reset-viewport-buffer!)
                     (js/console.log "[epupp:squirrel] Dismissed vanished cards"))]
    (r/render mount
      [:div {:id "epupp-squirrel-vanished-container"
             :style {:border-radius "8px" :overflow "hidden"
                     :border "2px solid #f59e0b" :margin "8px 0"}}
       [:div {:style {:padding "8px 12px" :background "#fffde7"
                      :display "flex" :align-items "center" :gap "8px"}}
        [:div {:style {:flex 1}}
         (epupp-header :size 20 :title "Squirrel" :tagline
                       (str (count snapshots) " vanished posts"))]
        [:button {:style {:background "none" :border "none" :cursor "pointer"
                          :font-size "18px" :color "#92400e" :padding "4px"
                          :line-height 1}
                  :title "Dismiss"
                  :on {:click on-dismiss}}
         "\u00D7"]]
       (for [snapshot snapshots]
         (vanished-post-card snapshot {:on-pin on-pin}))])))

(defn vanished-button-view [{:keys [n on-click on-dismiss]}]
  [:div {:style {:padding "12px 16px"
                 :margin "4px 0"
                 :background "#fffde7"
                 :border "2px solid #f59e0b"
                 :transition "background-color 0.3s ease-out, border-color 1s ease-out"
                 :border-radius "8px"
                 :display "flex"
                 :align-items "center"
                 :justify-content "center"
                 :gap "8px"}}
   [:div {:style {:cursor "pointer" :flex 1}
          :on {:click on-click}}
    (epupp-header :size 24 :title "Squirrel" :tagline
                  (str "Show " n " vanished posts"))]
   [:button {:style {:background "none" :border "none" :cursor "pointer"
                     :font-size "18px" :color "#92400e" :padding "4px"
                     :line-height 1}
             :title "Dismiss"
             :on {:click on-dismiss}}
    "\u00D7"]])

(defn inject-vanished-button! []
  (when-not (js/document.getElementById "epupp-squirrel-vanished-mount")
    (when-let [vanished-urns (detect-feed-refresh)]
      (let [{:keys [snapshots]} @!viewport-buffer
            vanished-snapshots (keep (fn [urn] (get snapshots urn)) vanished-urns)
            n (count vanished-snapshots)]
        (when (pos? n)
          (when-let [anchor (topmost-viewport-post-el)]
            (let [mount (js/document.createElement "div")]
              (set! (.-id mount) "epupp-squirrel-vanished-mount")
              (r/render mount
                (vanished-button-view
                 {:n n
                  :on-click (fn [_]
                              (render-vanished-cards! mount vanished-snapshots)
                              (js/console.log "[epupp:squirrel] Rendered" n "vanished post cards"))
                  :on-dismiss (fn [e]
                                (.stopPropagation e)
                                (.removeChild (.-parentElement mount) mount)
                                (reset-viewport-buffer!)
                                (js/console.log "[epupp:squirrel] Dismissed vanished-posts button"))}))
              (.insertBefore (.-parentElement anchor) mount anchor)
              (reset-viewport-buffer!)
              (js/setTimeout
               (fn []
                 (when-let [inner (.-firstElementChild mount)]
                   (set! (.. inner -style -backgroundColor) "transparent")
                   (set! (.. inner -style -borderColor) "#e5e7eb")))
               3000)
              (js/console.log "[epupp:squirrel] Feed refresh detected, injected vanished-posts button"))))))))

(defn create-viewport-observer! []
  (when-let [old (:resource/viewport-observer @!resources)]
    (.disconnect old))
  (let [observer (js/IntersectionObserver.
                  (fn [entries]
                    (when (on-feed-page?)
                      (doseq [entry entries]
                        (when (.-isIntersecting entry)
                          (let [post-el (.-target entry)
                                urn (extract-urn-from-element post-el)]
                            (when (activity-urn? urn)
                              (buffer-viewport-post! urn post-el)))))))
                  #js {:threshold 0.3})]
    (swap! !resources assoc :resource/viewport-observer observer)
    (js/console.log "[epupp:squirrel] Viewport observer started")
    observer))

(defn observe-feed-posts! []
  (when-let [observer (:resource/viewport-observer @!resources)]
    (doseq [post-el (qa-doc :sel/post-container)]
      (.observe observer post-el))))

(defn disconnect-viewport-observer! []
  (when-let [observer (:resource/viewport-observer @!resources)]
    (.disconnect observer)
    (swap! !resources assoc :resource/viewport-observer nil)))

(defn schedule-mutation-processing! []
  (when-let [raf (:resource/mutation-raf @!resources)]
    (js/cancelAnimationFrame raf))
  (when-let [timeout (:resource/mutation-timeout @!resources)]
    (js/clearTimeout timeout))
  (swap! !resources assoc :resource/mutation-raf
         (js/requestAnimationFrame
          (fn []
            (swap! !resources assoc :resource/mutation-timeout
                   (js/setTimeout process-mutations! 150))))))

(defn- ensure-iframe-observers! []
  (when-let [iframe-body (some-> (preload-iframe-doc) .-body)]
    (let [current-body (:resource/iframe-observed-body @!resources)]
      (when (not= current-body iframe-body)
        (when-let [old-observer (:resource/iframe-feed-observer @!resources)]
          (.disconnect old-observer))
        (let [observer (js/MutationObserver.
                        (fn [_mutations _observer]
                          (schedule-mutation-processing!)))]
          (.observe observer iframe-body
                    #js {:childList true :subtree true})
          (swap! !resources assoc
                 :resource/iframe-feed-observer observer
                 :resource/iframe-observed-body iframe-body))
        (attach-iframe-engagement-listener! iframe-body)
        (js/console.log "[epupp:squirrel] Attached to preload iframe")))))

(defn process-mutations! []
  (try
    (ensure-iframe-observers!)
    (when (on-feed-page?)
      (scan-visible-posts!)
      (observe-feed-posts!)
      (buffer-visible-viewport-posts!)
      (when (detect-feed-refresh)
        (inject-vanished-button!)))
    (ensure-nav-button!)
    (catch :default err
      (js/console.error "[epupp:squirrel] Mutation processing error:" err))))

(defn disconnect-feed-observer! []
  (when-let [observer (:resource/feed-observer @!resources)]
    (.disconnect observer))
  (when-let [observer (:resource/iframe-feed-observer @!resources)]
    (.disconnect observer))
  (when-let [raf (:resource/mutation-raf @!resources)]
    (js/cancelAnimationFrame raf))
  (when-let [timeout (:resource/mutation-timeout @!resources)]
    (js/clearTimeout timeout))
  (swap! !resources assoc
         :resource/feed-observer nil
         :resource/iframe-feed-observer nil
         :resource/iframe-observed-body nil
         :resource/mutation-raf nil
         :resource/mutation-timeout nil))

(defn create-feed-observer! []
  (disconnect-feed-observer!)
  (let [observer (js/MutationObserver.
                  (fn [_mutations _observer]
                    (schedule-mutation-processing!)))]
    (.observe observer js/document.body
              #js {:childList true :subtree true})
    (swap! !resources assoc :resource/feed-observer observer)
    (js/console.log "[epupp:squirrel] Feed observer started")))

(defn format-relative-time [iso-str now-ms]
  (let [then (.getTime (js/Date. iso-str))
        diff-ms (- now-ms then)
        minutes (Math/floor (/ diff-ms 60000))
        hours (Math/floor (/ minutes 60))
        days (Math/floor (/ hours 24))]
    (cond
      (< minutes 1) "just now"
      (< minutes 60) (str minutes "m ago")
      (< hours 24) (str hours "h ago")
      (= days 1) "yesterday"
      :else (str days "d ago"))))

(defn matches-search? [post search-text]
  (let [lower (string/lower-case search-text)]
    (or (string/includes? (string/lower-case (or (:post/author-name post) "")) lower)
        (string/includes? (string/lower-case (or (:post/text-preview post) "")) lower)
        (string/includes? (string/lower-case (or (:post/author-headline post) "")) lower))))

(defn filter-posts [posts {:keys [ui/search-text ui/filter-engagement ui/filter-media]}]
  (cond->> (vals posts)
    (and search-text (seq search-text))
    (filter #(matches-search? % search-text))
    filter-engagement
    (filter #(contains? (:post/engagements %) filter-engagement))
    filter-media
    (filter #(if (= filter-media :media/other)
               (contains? other-media-types (:post/media-type %))
               (= (:post/media-type %) filter-media)))))

(defn sort-posts [posts]
  (reverse (sort-by :post/last-engaged posts)))

(defn post-card [{:post/keys [urn media-type engagements pinned? last-engaged]
                  :as post}]
  [:div {:replicant/key urn
         :style {:padding "12px" :border-bottom "1px solid #e0e0e0"
                 :background (if pinned? "#fffde7" "white")
                 :cursor (if (string/starts-with? urn "urn:li:synthetic:") "default" "pointer")}
         :on {:click (fn [_e]
                       (when-not (string/starts-with? urn "urn:li:synthetic:")
                         (js/window.open (str "https://www.linkedin.com/feed/update/" urn "/") "_blank")))}}
   (post-card-body post
     [:div {:style {:display "flex" :align-items "center" :gap "4px"
                    :white-space "nowrap" :flex-shrink "0" :margin-top "2px"}}
      [:button {:style {:background "none" :border "none" :cursor "pointer"
                        :font-size "14px" :padding "0" :line-height "1"
                        :color (if pinned? "#f59e0b" "#ccc")}
                :title (if pinned? "Unpin" "Pin")
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (storage-transact! toggle-pin urn))}}
       (if pinned? "\u2605" "\u2606")]
      [:span {:style {:font-size "11px" :color "#999" :line-height "1"}}
       (format-relative-time last-engaged (js/Date.now))]
      (when-not (= urn genesis-post-urn)
        [:button {:style {:background "none" :border "none" :cursor "pointer"
                          :color "#4c4c4c" :font-size "20px" :padding "6px"
                          :line-height "1" :margin-left "2px"}
                  :title "Remove from hoard"
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (storage-transact! remove-post urn))}}
         "\u00D7"])])
   [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap"}}
    (when media-type
      [:span {:style {:background "#e3f2fd" :color "#1565c0" :padding "2px 6px"
                      :border-radius "4px" :font-size "10px"}}
       (get media-labels media-type "?")])
    (for [eng (sort (map name engagements))]
      [:span {:replicant/key eng
              :style {:background "#f3e5f5" :color "#7b1fa2" :padding "2px 6px"
                      :border-radius "4px" :font-size "10px"}}
       eng])]
   (when (= urn genesis-post-urn)
     [:div {:style {:font-size "11px" :color "#888" :margin-top "4px"}}
      "Please visit and like/comment/share \u2764\uFE0F"])])

(defn panel-view [state]
  (let [{:keys [squirrel/posts ui/search-text ui/filter-engagement ui/filter-media]} state
        filtered (filter-posts posts state)
        sorted (sort-posts filtered)
        {current-posts false stale-posts true} (group-by stale-images? sorted)
        show-stale? (:ui/show-legacy? state)
        post-count (count posts)]
    [:div {:id "epupp-squirrel-panel"
           :style {:position "fixed" :top "52px" :right "0" :bottom "0"
                   :width "380px" :background "white" :z-index "9999"
                   :box-shadow "-2px 0 12px rgba(0,0,0,0.15)"
                   :display "flex" :flex-direction "column"
                   :font-family "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"}}
     ;; Header
     [:div {:style {:padding "12px 16px" :border-bottom "1px solid #e0e0e0"
                    :display "flex" :justify-content "space-between" :align-items "center"}}
      (epupp-header :size 28 :title (str "Hoarded Posts (" post-count ")") :tagline nil)
      [:button {:style {:background "none" :border "none" :cursor "pointer"
                        :font-size "20px" :color "#666" :padding "0 4px"}
                :on {:click (fn [_] (swap! !state assoc :ui/panel-open? false))}}
       "\u00d7"]]
     ;; Search
     [:div {:style {:padding "8px 16px"}}
      [:input {:type "text"
               :placeholder "Text for filtering hoarded posts"
               :value (or search-text "")
               :style {:width "100%" :padding "6px 10px" :border "1px solid #ccc"
                       :border-radius "4px" :font-size "13px" :box-sizing "border-box"}
               :on {:input (fn [e] (swap! !state assoc :ui/search-text (.. e -target -value)))}}]]
     ;; Filter chips
     [:div {:style {:padding "4px 16px" :display "flex" :gap "4px" :flex-wrap "wrap"}}
      (for [[k label] engagement-labels]
        [:button {:replicant/key (name k)
                  :style {:padding "3px 8px" :border-radius "12px" :font-size "11px"
                          :cursor "pointer" :border "1px solid #ccc"
                          :background (if (= filter-engagement k) "#0a66c2" "white")
                          :color (if (= filter-engagement k) "white" "#333")}
                  :on {:click (fn [_]
                                (swap! !state assoc :ui/filter-engagement
                                       (when (not= filter-engagement k) k)))}}
         label])]
     [:div {:style {:padding "4px 16px" :display "flex" :gap "4px" :flex-wrap "wrap"}}
      (for [[k label] media-filter-labels]
        [:button {:replicant/key (name k)
                  :style {:padding "3px 8px" :border-radius "12px" :font-size "11px"
                          :cursor "pointer" :border "1px solid #ccc"
                          :background (if (= filter-media k) "#6b21a8" "white")
                          :color (if (= filter-media k) "white" "#333")}
                  :on {:click (fn [_]
                                (swap! !state assoc :ui/filter-media
                                       (when (not= filter-media k) k)))}}
         label])]
     ;; Post count
     [:div {:style {:padding "4px 16px" :font-size "11px" :color "#666"}}
      [:span (str (count current-posts) " matching posts"
                  (when (seq stale-posts)
                    (str " + " (count stale-posts) " stale")))]]
     ;; Post list
     [:div {:style {:flex "1" :overflow-y "auto" :overscroll-behavior "contain"}}
      (if (seq sorted)
        [:div
         (for [post current-posts]
           (post-card post))
         (when (seq stale-posts)
           [:div
            [:button {:style {:width "100%" :padding "8px 16px" :border "none"
                              :background "#f5f5f5" :cursor "pointer"
                              :font-size "12px" :color "#666"
                              :border-top "1px solid #e0e0e0"
                              :border-bottom "1px solid #e0e0e0"
                              :text-align "left"}
                      :on {:click (fn [_] (swap! !state update :ui/show-legacy? not))}}
             (str (if show-stale? "\u25bc" "\u25b6") " Stale images (" (count stale-posts) ") \u2014 visit to heal")]
            (when show-stale?
              (for [post stale-posts]
                (post-card post)))])]
        [:div {:style {:padding "32px" :text-align "center" :color "#999"}}
         "No hoarded posts yet"])]]))

(defn render-panel! []
  (let [container (:resource/panel-container @!resources)]
    (if (:ui/panel-open? @!state)
      (r/render container (panel-view @!state))
      (r/render container nil))))

(defn attach-escape-handler! []
  (attach-listener! js/document "keydown" :resource/keydown-handler
                    (fn [e]
                      (when (and (= (.-key e) "Escape")
                                 (:ui/panel-open? @!state))
                        (swap! !state assoc :ui/panel-open? false)))
                    {}))

(defn detach-escape-handler! []
  (detach-listener! js/document "keydown" :resource/keydown-handler {}))

(defn attach-click-outside-handler! []
  (attach-listener! js/document "click" :resource/click-outside-handler
                    (fn [e]
                      (when (:ui/panel-open? @!state)
                        (let [panel (js/document.getElementById "epupp-squirrel-panel")
                              nav-btn (js/document.getElementById "epupp-squirrel-nav-btn")]
                          (when (and panel
                                     (not (.contains panel (.-target e)))
                                     (or (nil? nav-btn)
                                         (not (.contains nav-btn (.-target e)))))
                            (swap! !state assoc :ui/panel-open? false)))))
                    {}))

(defn detach-click-outside-handler! []
  (detach-listener! js/document "click" :resource/click-outside-handler {}))

(defn attach-beforeunload-handler! []
  (attach-listener! js/window "beforeunload" :resource/beforeunload-handler
                    (fn [_e] (save-state!))
                    {}))

(defn detach-beforeunload-handler! []
  (detach-listener! js/window "beforeunload" :resource/beforeunload-handler {}))

(defn poll-until-ready! []
  (let [attempts (atom 0)
        max-attempts 30
        interval-ms 200]
    (letfn [(tick []
              (swap! attempts inc)
              (ensure-panel-container!)
              (detect-current-user-slug!)
              (scan-visible-posts!)
              (let [nav-done? (ensure-nav-button!)]
                (when (and (not nav-done?)
                           (< @attempts max-attempts))
                  (js/setTimeout tick interval-ms))))]
      (js/setTimeout tick interval-ms))))

(defn on-navigation! []
  (let [current (.-href js/window.location)]
    (when (not= current (:nav/last-url @!state))
      (swap! !state assoc
             :nav/seen-urns #{}
             :nav/last-url current
             :ui/panel-open? false)
      (reset-viewport-buffer!)
      (poll-until-ready!)
      (js/setTimeout hoard-visited-post! 2000))))

(defn start-url-polling! []
  (when-let [old (:resource/url-poll-interval @!resources)]
    (js/clearInterval old))
  (swap! !state assoc :nav/last-url (.-href js/window.location))
  (swap! !resources assoc :resource/url-poll-interval
         (js/setInterval
          (fn []
            (let [current (.-href js/window.location)]
              (when (not= current (:nav/last-url @!state))
                (on-navigation!))))
          2000)))

(defn stop-url-polling! []
  (when-let [interval (:resource/url-poll-interval @!resources)]
    (js/clearInterval interval)
    (swap! !resources assoc :resource/url-poll-interval nil)))

(defn attach-popstate-handler! []
  (attach-listener! js/window "popstate" :resource/popstate-handler-fn
                    (fn [_e] (on-navigation!))
                    {}))

(defn detach-popstate-handler! []
  (detach-listener! js/window "popstate" :resource/popstate-handler-fn {}))

(defn handle-storage-change! [e]
  (when (= (.-key e) storage-key)
    (when-let [new-val (.-newValue e)]
      (try
        (let [{:keys [posts index]} (clojure.edn/read-string new-val)]
          (swap! !state assoc
                 :squirrel/posts (or posts {})
                 :squirrel/index (or index []))
          (js/console.log "[epupp:squirrel] Storage updated from other tab,"
                          (count posts) "posts"))
        (catch :default err
          (js/console.error "[epupp:squirrel] Storage change error:" err))))))

(defn attach-storage-listener! []
  (attach-listener! js/window "storage" :resource/storage-handler
                    handle-storage-change! {}))

(defn detach-storage-listener! []
  (detach-listener! js/window "storage" :resource/storage-handler {}))

(defn teardown! []
  (disconnect-feed-observer!)
  (disconnect-viewport-observer!)
  (detach-engagement-listener!)
  (detach-comment-input-listener!)
  (detach-iframe-engagement-listener!)
  (detach-escape-handler!)
  (detach-click-outside-handler!)
  (detach-beforeunload-handler!)
  (detach-popstate-handler!)
  (detach-storage-listener!)
  (stop-url-polling!)
  (save-state!)
  (when-let [m (:resource/nav-mount @!resources)]
    (r/render m nil))
  (when-let [container (:resource/panel-container @!resources)]
    (r/render container nil))
  :torn-down)

(defn init! []
  (remove-watch !state ::panel-renderer)
  (add-watch !state ::panel-renderer
             (fn [_k _r o n]
               (when (not= o n)
                 (render-panel!)
                 (ensure-nav-button!))))
  (ensure-panel-container!)
  (load-state!)
  (ensure-genesis-post!)
  (create-feed-observer!)
  (create-viewport-observer!)
  (attach-engagement-listener!)
  (attach-comment-input-listener!)
  (attach-escape-handler!)
  (attach-click-outside-handler!)
  (attach-beforeunload-handler!)
  (attach-popstate-handler!)
  (start-url-polling!)
  (ensure-nav-button!)
  (attach-storage-listener!)
  (js/setTimeout (fn []
                   (detect-current-user-slug!)
                   (scan-visible-posts!)
                   (buffer-visible-viewport-posts!)
                   (hoard-visited-post!)) 1000)
  (selector-health-check!)
  (js/console.log "[epupp:squirrel] Initialized")
  :initialized)

(init!)

(comment
  (teardown!)
  :rcf)

