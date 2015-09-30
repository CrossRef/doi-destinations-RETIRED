(ns member-domains.core
  (:require [member-domains.state :as state]
            [member-domains.db :as db]
            [member-domains.etld :as etld])
  (:require [crossref.util.doi :as crdoi])
  (:require [korma.core :as k])
  (:require [clj-http.client :as client]
            [robert.bruce :refer [try-try-again]]
            [clojure.data.json :as json])
  (:require [clojure.java.io :refer [resource]])
  (:gen-class))

(def members-endpoint "http://api.crossref.org/v1/members")
(def member-page-size 100)
(def sample-size 10)

(defn mark-ignored
  "Ignore chosen domains in ignore.txt"
  []
  (with-open [reader (clojure.java.io/reader (resource "ignore.txt"))]
    (let [lines (line-seq reader)]
      (doseq [line lines]
        (k/update db/member-domains
          (k/where {:domain [like (str line)]})
          (k/set-fields {:ignored true}))))))

(defn big-pmap
  "Copy of pmap with lots of threads."
  ([f coll]
   (let [n 100
         rets (map #(future (f %)) coll)
         step (fn step [[x & xs :as vs] fs]
                (lazy-seq
                 (if-let [s (seq fs)]
                   (cons (deref x) (step xs (rest s)))
                   (map deref vs))))]
     (step rets (drop n rets)))))

(defn- fetch-member-ids []
  (loop [ids-acc []
         page 0]
    (prn page (count ids-acc))
    (let [response (client/get (str members-endpoint \? (client/generate-query-string
                                                          {:rows member-page-size :offset (* page member-page-size)})))
          body (json/read-str (:body response) :key-fn keyword)
          ids (map :id (-> body :message :items))]
      
      (if (empty? ids)
        ids-acc
        (recur (concat ids-acc ids) (inc page))))))

(defn- doi-sample-for-member [member-id]
  (let [response (client/get (str members-endpoint "/" member-id "/works" \? (client/generate-query-string
                                                          {:sample sample-size})))
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
                              (let [first-host (.getHost (new java.net.URL (first result)))
                                    second-host (.getHost (new java.net.URL (second result)))]
                             (swap! doi-counter inc)
                             [first-host second-host doi member-id])
                             (catch Exception e (do
                                                  (prn "ERROR" result)
                                                  nil)))))
                      batch)
        results (filter identity results)]
    
    (add-watch doi-counter :dois (fn [k r old-state new-state]
                                (when (zero? (mod new-state 100))
                                  (prn "DOI" new-state))))
    
    (doseq [[first-domain last-domain doi member-id] results] 
      (db/ensure-domain member-id first-domain)
      (db/ensure-domain member-id last-domain)
      (db/mark-doi-resolved doi))
    @doi-counter))

(defn run-all-resolution-batch
  "Resolve all DOIs until there are no more left to resolve."
  []
  (loop []
    (let [num-resolved (run-resolution-batch)]
      (when (not (zero? num-resolved))
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
  (println (clojure.string/join "|"
                       (map (fn [entry]
         (let [[subdomain domain etld] (etld/get-main-domain entry)]   
          (str domain "\\." etld)))
         (db/unique-member-domains)))))

; Main functions

(defn grab-dois
  "Get a sample of DOIs per publisher."
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
    (reset! state/member-ids (fetch-member-ids))  
    (prn "Got" (count @state/member-ids) " member ids")
    (let [all-dois (pmap (fn [member-id]
                    (let [dois (doi-sample-for-member member-id)]
                      (swap! member-counter inc)
                      [member-id dois])) @state/member-ids)]
      (doseq [[member-id dois] all-dois]
        (doseq [doi dois]
          (swap! doi-counter inc)
          (db/ensure-doi member-id doi))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (condp = (first args)
    "mark-ignored" (mark-ignored)
    "dois" (grab-dois)
    "resolve" (run-resolution-batch)
    "dump" (dump)
    "dump-domains" (dump-domains)
    "regular-expression" (dump-regular-expression)
    ))
