(ns member-domains.core
  (:require [member-domains.state :as state]
            [member-domains.db :as db]
            [member-domains.etld :as etld]
            [member-domains.gcs :as gcs]
            [member-domains.lookup :as lookup]
            [member-domains.server :as server])
  (:require [crossref.util.doi :as crdoi])
  (:require [korma.core :as k])
  (:require [clj-http.client :as client]
            [robert.bruce :refer [try-try-again]]
            [clojure.data.json :as json])
  (:require [clojure.java.io :refer [resource]]
            [clojure.string :as string])
  (:gen-class))

(def members-endpoint "http://api.crossref.org/v1/members")
(def member-page-size 100)
(def sample-size 1000)

(defn mark-ignored
  "Ignore chosen domains in ignore.txt"
  []
  (with-open [reader (clojure.java.io/reader (resource "ignore.txt"))]
    (let [lines (line-seq reader)]
      (doseq [line lines]
        (when-not (clojure.string/blank? line)
          (k/update db/member-domains
            (k/where {:domain [like (str line)]})
            (k/set-fields {:ignored true}))

          (k/update db/member-domains
            (k/where {:domain [like (str "%." line)]})
            (k/set-fields {:ignored true})))))))

(defn big-pmap
  "Copy of pmap with lots of threads."
  ([f coll]
   (let [n 1000
         rets (map #(future (f %)) coll)
         step (fn step [[x & xs :as vs] fs]
                (lazy-seq
                 (if-let [s (seq fs)]
                   (cons (deref x) (step xs (rest s)))
                   (map deref vs))))]
     (step rets (drop n rets)))))

(defn- fetch-member-info []
  (loop [info-acc []
         page 0]
    (prn "Page:" page " members:" (count info-acc))
    (let [response (client/get (str members-endpoint \? (client/generate-query-string
                                                          {:rows member-page-size :offset (* page member-page-size)})))
          body (json/read-str (:body response) :key-fn keyword)
          info (map #(select-keys % [:id :prefixes]) (-> body :message :items))]
      (if (empty? info)
        info-acc
        (recur (concat info-acc info) (inc page))))))

(defn- doi-sample-for-member [member-id]
  (let [response (try-try-again {:sleep 500 :tries 10} #(client/get (str members-endpoint "/" member-id "/works" \? (client/generate-query-string
                                                            {:sample sample-size}))))
          body (json/read-str (:body response) :key-fn keyword)
          dois (map :DOI (-> body :message :items))]
  dois))

(defn- resolve-doi
  "Get the first and last redirects or nil if it doesn't exist."
  [doi]
  (try 
    (locking *out* (prn "Resolve" doi))
    (let [url (crdoi/normalise-doi doi)
          result (try-try-again {:sleep 500 :tries 2} #(client/head url
                                                         {:follow-redirects true
                                                          :throw-exceptions true
                                                          :socket-timeout 5000
                                                          :conn-timeout 5000
                                                          :headers {"Referer" "chronograph.crossref.org"
                                                                    "User-Agent" "CrossRefDOICheckerBot (labs@crossref.org)"}}))
          _ (locking *out* (prn "-> " doi))
          ; TODO need to look in :opts first?
          ; Drop the initial dx.doi.org
          redirects (rest (:trace-redirects result))
          first-redirect (first redirects)
          last-redirect (last redirects)
          ok (= 200 (:status result))]
          ; (locking *out* (prn "Finish resolve" doi ok))
          (when ok
            [first-redirect last-redirect (count redirects)]))
    (catch Exception ex (let [message (.getMessage ex)]
                          ; When it's FTP we can't follow, but we can say that we tried.
                          ; Message is: Scheme 'ftp' not registered.
                          (when (and message (.contains message "ftp"))
                            (try
                              (let [connection (.openConnection (new java.net.URL (crdoi/normalise-doi doi)))
                                    first-redirect-url (.getHeaderField connection "Location")]
                                ; We didn't actually follow, so record the number of redirects as zero.
                                [first-redirect-url "" 0])
                              
                              ; It may still go wrong, in which case just return nothing.
                              (catch Exception _ nil)))))))

(defn run-resolution-batch
  "Resolve a batch of DOIs. One per member so we minimise the chance hitting any particular server hard."
  []
  (let [doi-counter (atom 0)
        batch (db/get-resolution-batch)
        results (big-pmap (fn [{doi :doi member-id :uniq-member-id}]
                          (when-let [result (resolve-doi doi)]
                            (try
                              (let [[first-url last-url] result
                                    first-host (.getHost (new java.net.URL first-url))
                                    last-host (.getHost (new java.net.URL last-url))]
                             (swap! doi-counter inc)
                             [first-host last-host first-url last-url doi member-id])
                             (catch Exception e (do
                                                  (prn "ERROR" result)
                                                  nil)))))
                      batch)
        results (filter identity results)]
    
    (add-watch doi-counter :dois (fn [k r old-state new-state]
                                (when (zero? (mod new-state 100))
                                  (prn "DOI" new-state))))
    
    (doseq [[first-domain last-domain first-url last-url doi member-id] results] 
      (db/ensure-domain member-id first-domain)
      (db/ensure-domain member-id last-domain)
      (db/update-doi-urls doi first-url last-url)
      (db/mark-doi-resolved doi))
    @doi-counter))

(defn run-all-resolution-batch
  "Resolve all DOIs until there are no more left to resolve."
  []
  (loop []
    (prn "Before batch run, remaining DOIs: " (db/num-unresolved-dois))
    (run-resolution-batch)
    (let [num-unresolved (db/num-unresolved-dois)]
      (prn "After batch run, remaining DOIs " num-unresolved)
      (when (> num-unresolved 0)
        (recur)))))

(defn dump
  "Dump to stdout. Each line is full domain, space, domain name"
  []
  (let [entries (db/unique-member-domains)]
    (doseq [entry entries]
      (println entry " " (second (etld/get-main-domain entry))))))


(defn dump-domains
  "Dump domains to stdout. Each line is full domain"
  []
  (let [entries (db/unique-member-domains)]
    (doseq [entry entries]
      (let [[subdomain domain etld] (etld/get-main-domain entry)]
      (println (str domain "." etld))))))

(defn dump-regular-expression
  "Dump domains to stdout. Each line is full domain"
  []
  (println (string/join "|"
                       (map (fn [entry]
         (let [[subdomain domain etld] (etld/get-main-domain entry)]   
          (str domain "\\." etld)))
         (db/unique-member-domains)))))

(defn update-from-api
  "Get a sample of DOIs per publisher and other info."
  []
  (let [member-counter (atom 0)
        doi-counter (atom 0)]
    (add-watch member-counter :members (fn [k r old-state new-state]
                                (when (zero? (mod new-state 100))
                                  (prn "Member" new-state))))
    (add-watch doi-counter :dois (fn [k r old-state new-state]
                                (when (zero? (mod new-state 1000))
                                  (prn "DOI" new-state))))
    (println "Fetch member ids")
    (reset! state/member-info (fetch-member-info))  
    (prn "Got" (count @state/member-info) " members' info")
    
    (doseq [{member-id :id member-prefixes :prefixes} @state/member-info]
      (doseq [member-prefix member-prefixes]
        (db/ensure-member-prefix member-id member-prefix)))
    
    (let [all-dois (pmap (fn [{member-id :id}]
                    (let [dois (doi-sample-for-member member-id)]
                      (swap! member-counter inc)
                      [member-id dois])) @state/member-info)]
      (doseq [[member-id dois] all-dois]
        (doseq [doi dois]
          (swap! doi-counter inc)
          (db/ensure-doi member-id doi))))))

(defn -main
  [& args]
  (condp = (first args)
    "server" (server/start)
    "mark-ignored" (mark-ignored)
    "update" (update-from-api)
    "resolve-all" (run-all-resolution-batch)
    "dump" (dump)
    "dump-domains" (dump-domains)
    "dump-common-substrings" (gcs/dump-common-substrings)
    "regular-expression" (dump-regular-expression)
    ; Bifurcate a file of URLs using the 'embedded url/string' method and put successes an failures into given files.
    "bifurcate-lookup-url-embed" (lookup/bifurcate :get-embedded-doi-from-string  (nth args 1) (nth args 2) (nth args 3))))
