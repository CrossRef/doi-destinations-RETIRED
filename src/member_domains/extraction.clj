(ns member-domains.extraction
  "Extract DOIs from HTML."
  (:require [member-domains.structured-extraction]
            [member-domains.unstructured-extraction]))


(defn fetch-with-cookies
  "Fetch a URL, following redirects and accepting cookies along the way."
  [url]
  (loop [headers {}
         depth 0
         url url]
    (if (> depth 4)
      nil
      (let [result @(client/get url {:follow-redirects false :headers headers})
            cookie (-> result :headers :set-cookie)
            new-headers (merge headers (when cookie {"Cookie" cookie}))]
         (condp = (:status result)
          200 result
          302 (recur new-headers (inc depth) (-> result :headers :location))
          nil)))))


(defn extract-from-url
  "Given a URL, extract the most likely valid DOI."
  [url]
  (let [data (fetch-with-cookies url)
        from-tags (structured-extraction/from-tags html)
        from-webpage (unstructured-extraction/from-webpage html url)
        candidates (concat from-tags from-webpage)
        ]
  
  ))
