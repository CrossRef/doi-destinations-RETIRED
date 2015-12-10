(ns member-domains.gcs
  "Greatest common substrings. Find substrings to cut down linear search space when finding member domains.
  The problem of searching for all member domains is that it's a very long linear search, one per domain.
  We can do one much smaller linear search for substrings, which will partition the search space massively.
  Then we can do a secondary linear search for only those domains of which the substring is a factor.
  
  There isn't a single greatest common substring, at least not a useful one (maybe '.'), so find a sensible
  set of them (~500).
  
  This cuts ~3000 domains down to ~500 initial comparisons.
  "
  (:require [member-domains.db :as db])
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [member-domains.etld :as etld]))

(def word-count-threshold
  "Minimum number of domains that should match a substring.
  Too low and there are lots of substrings each with few secondary searches, few left-overs.
  Too high and there are few substrings with many secondary searches, plus lots of singleton left-overs."
  20)

; 4 => 405 average, 7.3 secondary
; 5 => 381 average 7.7 secondary
; 10 => 438 substrings, average 6.7 secondary
; 20 => 523 substrings, average 5.6

(defn substrings-of-length
  "All substrings of given length in the seq of inputs"
  [inputs length]
  (let [substrings (mapcat
                      (fn [input]
                        (map #(.substring input % (+ length %)) (range (- (.length input) length))))
                      inputs)]
    substrings))
        
(defn best-substring-of-length
  "Find the best substring of the given length and return it and all the inputs that don't contain it." 
  [inputs length]
  (let [substrings (substrings-of-length inputs length)]
    (if (empty? substrings)
      [nil 0 inputs]
      (let [freqs (frequencies substrings)
        best-freq (apply max (map second freqs))
        matching-words (keep (fn [[word cnt]]
                               (when (= cnt best-freq)
                                 word)) freqs)
        ; Words have the same frequency and length, nothing to choose between them.
        word (first matching-words)
        remaining (remove #(.contains % word) inputs)]
    (if (> best-freq word-count-threshold)
      [word best-freq remaining]
      [nil 0 remaining])))))

(defn best-substrings-of-length
  "Find the best substrings of the given length and the remaining inputs."
  [inputs length]
  (loop [inputs inputs
         substrings-and-freqs nil]
    (let [[substring freq remaining] (best-substring-of-length inputs length)]
      ; Stop recursing when we stop finding substrings of that length.
      (if substring
        (recur remaining (conj substrings-and-freqs [substring freq]))
        [substrings-and-freqs remaining]))))

(defn common-substrings
  "Generate structure of common substrings"
  []
  (let [all-domains (db/unique-member-domains)
        ; Domains without the 'www' or TLD - they would be useless for separating out domains.
        sensible-domains (map (fn [domain]
                       (->> domain etld/get-main-domain
                           (take 2)
                           (string/join ".")
                           (#(if (.startsWith % "www.")
                               (.substring % 4)
                               %)))) all-domains)
        
        max-length (apply max (map #(.length %) sensible-domains))
        
        substrings (loop [length max-length
                          inputs sensible-domains
                          substring-and-freqs-acc nil]
                      (let [[substrings-and-freqs remaining] (best-substrings-of-length inputs length)]  
                        (if (> length 1)
                          ; Keep going all the way down.
                          (recur (dec length) remaining (concat substring-and-freqs-acc substrings-and-freqs))
                          
                          ; Base case.
                          ; There will be some left that don't meet word-count-threshold
                          ; These must be included. We tried.
                          ; Mark the stragglers as having freq 1 (although that's not true) because freq is
                          ; used to sort and we want them at the end of the list.
                           (concat substring-and-freqs-acc
                                   substrings-and-freqs
                                   ; The substrings that didn't make the cut.
                                   (map #(vector % 1) remaining)
                                   ; And the domains that didn't make the cut as a result
                                   (map #(vector %1) inputs)))))
        
        ; TODO how to sort? By count gives "e" first, which is probably not a good idea
        ; as it would trigger a secondary linear search of a large number of dregs.
        ; By inverse count would mean smaller secondary linear search, but more of them.
        ; By length means frequency distribution is all over the place.
        ; There must be some clever ranking.
        ; Also affects grouping as that's order-sensitive.
        sorted-substrings (reverse (sort-by #(-> % first count) substrings))
        
        [groups remaining] (reduce (fn [[result-acc domains-to-filter] [substring _]]
                                      (let [matching-domains (doall (filter #(.contains % substring) domains-to-filter))
                                            non-matching-domains (doall (remove #(.contains % substring) domains-to-filter))
                                            
                                            new-result-acc (conj result-acc [substring matching-domains])]
                                        
                                      [new-result-acc non-matching-domains])) [[] all-domains] sorted-substrings)
        
        ; Some substrings will be empty as they're vacuumed up by others.
        result-groups (remove #(empty? (second %)) groups)
        empty-groups (filter #(empty? (second %)) groups)]
    
    ; Sanity check. Everything should be covered.
    (assert (empty remaining))
    ; (prn "Total result groups " (count result-groups))
    ; (prn "Average secondary" (float (/ (apply +   (map #(-> % second count) result-groups)) (count result-groups))))
    
    result-groups))

(defn dump-common-substrings
  "Write the whole lot out to stout."
  []
  (json/pprint (common-substrings)))


