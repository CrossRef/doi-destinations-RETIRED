(ns member-domains.lookup-batch-test
  (:require [clojure.test :refer :all]
            [member-domains.lookup :refer :all]))


(deftest get-doi-from-url-batch
  ; This is more of a smoke test for a load of URLs found in the wild. Ensure that each one returns something. 
  ; Given that the process awlays includes checking the DOI exists, this should be fine.
  (testing "Batch of URLs containing DOI prefix can have DOIs extracted from them.")
  (let [urls (line-seq (clojure.java.io/reader "resources/test/doi-embedded-in-url.txt"))
        results (pmap #(vector % (get-embedded-doi-from-url %)) urls)]
    (doseq [[url doi] results]
      ; Just check that it returns something.
      (is doi url))))



