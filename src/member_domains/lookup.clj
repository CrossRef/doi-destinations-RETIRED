(ns member-domains.lookup
  (:require [member-domains.db :as db])
  (:require [crossref.util.doi :as crdoi])
  (:require [clojure.string :as string])
  (:require [clojure.tools.logging :refer [info]])
  (:require [net.cgrand.enlive-html :as html]
            [cemerick.url :as cemerick-url]
            [robert.bruce :refer [try-try-again]]
            [org.httpkit.client :as http])
  (:import [java.net URL URI]))

(def whole-doi-re #"^10\.\d{4,9}/[^\s]+$")
(def doi-re #"10\.\d{4,9}/[^\s]+")


(defn try-url
  "Try to construct a URL."
  [text]
  (try (new URL text) (catch Exception _ nil)))

(defn doi-from-url
  "If a URL is a DOI, return the non-URL version of the DOI."
  [text]
  (when-let [url (try-url text)]
    (when (#{"doi.org" "dx.doi.org"} (.getHost url))
      (.substring (or (.getPath url) "") 1))))

(defn matches-doi?
  "Does this look like a DOI?"
  [input]
  (and (not (string/blank? input)) (re-matches whole-doi-re input)))

(defn remove-doi-colon-prefix
  "Turn 'doi:10.5555/12346789' into '10.5555/12345678'"
  [input]
  (when-let [match (re-matches #"^[a-zA-Z ]+: ?(10\.\d+/.*)$" input)]
    (.toLowerCase (second match))))

(defn get-doi-from-get-params
  "If there's a DOI in a get parameter of a URL, return it"
  [url]
  (let [params (-> url cemerick-url/query->map clojure.walk/keywordize-keys)
        doi-like (keep (fn [[k v]] (when (re-matches whole-doi-re v) v)) params)]
    (first doi-like)))

(defn cleanup-doi
  "Take a URL or DOI or something that could be a DOI, return the DOI if it is one."
  [potential-doi]
  (let [normalized-doi (crdoi/non-url-doi potential-doi)
        doi-colon-prefixed-doi (remove-doi-colon-prefix potential-doi)]

  ; Find the first operation that produces an output that looks like a DOI.
  (cond
    (matches-doi? normalized-doi) normalized-doi
    (matches-doi? doi-colon-prefixed-doi) doi-colon-prefixed-doi
    :default nil)))

(defn extract-text-fragments-from-html
  "Extract all text from an HTML document."
  [input]
  (string/join " "
    (-> input
    (html/html-snippet)
    (html/select [:body html/text-node])
    (html/transform [:script] nil)
    (html/texts))))


(defn extract-doi-in-a-hrefs-from-html
  "Extract all <a href> links from an HTML document."
  [input]
    (let [links (html/select (html/html-snippet input) [:a])
          hrefs (keep #(-> % :attrs :href) links)
          dois (keep doi-from-url hrefs)]
      (distinct dois)))

(defn extract-dois-from-text
  [text]
  (let [matches (re-seq doi-re text)]
        (distinct matches)))

(def max-drops 10)
(defn validate-doi
  "For a given suspected DOI, validate that it exists against the API, possibly modifying it to get there."
  [doi]
  (loop [i 0
         doi doi]
    ; Terminate if we're at the end of clipping things off or the DOI no longer looks like an DOI. 
    ; The API will return 200 for e.g. "10.", so don't try and feed it things like that.
    (if (or (= i max-drops) (< (.length doi) i) (not (re-matches doi-re doi)))
      nil
      (if (-> (try-try-again {:sleep 500 :tries 2} #(http/get (str "http://api.crossref.org/v1/works/" doi)))
              deref
              :status
              (= 200))
      doi
      (recur (inc i) (.substring doi 0 (- (.length doi) 1)))))))

(defn url-matches-doi?
  "Does the given DOI resolve to the given URL? Return DOI if so."
  [url doi]
  (info "Check " url " for " doi)
  (when-let [result (try-try-again {:sleep 500 :tries 2} #(http/get (str "http://doi.org/" doi)
                                                         {:follow-redirects true
                                                          :throw-exceptions true
                                                          :socket-timeout 5000
                                                          :conn-timeout 5000
                                                          :headers {"Referer" "chronograph.crossref.org"
                                                                    "User-Agent" "CrossRefDOICheckerBot (labs@crossref.org)"}}))]
    (let [doi-urls (set (conj (-> @result :opts :trace-redirects) (-> @result :opts :url)))
          url-match (doi-urls url)]
      (when url-match
        doi))))

(defn resolve-doi-from-url
  "Take a URL and try to resolve it to find what DOI it corresponds to."
  [url]
  (info "Try to resolve:" url)
  (when-let [result (try-try-again {:sleep 500 :tries 2} #(http/get url
                                                         {:follow-redirects true
                                                          :throw-exceptions true
                                                          :socket-timeout 5000
                                                          :conn-timeout 5000
                                                          :headers {"Referer" "chronograph.crossref.org"
                                                                    "User-Agent" "CrossRefDOICheckerBot (labs@crossref.org)"}}))]
    (let [body (:body @result)
          text (extract-text-fragments-from-html body)
          
          dois-from-text (extract-dois-from-text text)
          links-from-text (extract-doi-in-a-hrefs-from-html body)

          ; Look at the first DOI in the text before any of the others - high chance that it's the first one.
          first-text-doi (url-matches-doi? url (first dois-from-text))]

    (info "Found" (count dois-from-text) "DOIs in text," (count links-from-text) "DOIs from links")
    (info "Match for first doi" (first dois-from-text) ":" first-text-doi)

      ; If the first DOI in the text doesn't work then we need to start looking at the rest.
      (if first-text-doi
          first-text-doi
          (let [; Now we have a set of DOIs we think it could be. Try to resolve each one to see if we end up in the same place.
                potential-dois (concat (rest dois-from-text) links-from-text)

                ; Validate ones that exist. The regular expression might be a bit greedy, so this may chop bits off the end to make it work.
                extant-dois (keep validate-doi potential-dois)

                ; Matched DOIs. Some DOIs may map to the same page, e.g. components in PLOS.
                ; e.g. "10.1371/journal.pone.0144297.g002" goes to the sampe place as "10.1371/journal.pone.0144297"
                ; In the case of multiple results, choose the shortest.
                ; This does lots of network requests, so pmap is useful.
                matched-url-doi (->> extant-dois
                                     (pmap #(url-matches-doi? url %))
                                     (filter identity)
                                     ; sort by length of DOI.
                                     (sort-by count)
                                     first)]
            (info "Found matching DOI:" matched-url-doi)
          matched-url-doi)))))

(defn lookup-uncached
  "As lookup, but without the cache"
  [input]
  ; Try to treat it as a DOI in a particular encoding.
  (if-let [cleaned-doi (cleanup-doi input)]
    cleaned-doi

    ; Try to treat it as a Publisher URL that has a DOI in the URl.
    (if-let [embedded-doi (get-doi-from-get-params input)]
      embedded-doi

      ; Try to treat it as a Publisher URL that must be fetched to extract its DOI.
    (if-let [resolved-doi (resolve-doi-from-url input)]
      resolved-doi
      nil))))


(defn lookup
  "Look up some input and turn it into a DOI. Could be a URL landing page or a DOI in any form. Cached."
  [url]
  (info "Lookup" url)
  (if-let [doi (db/get-cache-doi-for-url url)]
    doi
    (when-let [doi (lookup-uncached url)]
      (db/set-cache-doi-for-url url doi)
      doi)))