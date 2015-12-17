(ns member-domains.lookup-test
  (:require [clojure.test :refer :all]
            [member-domains.lookup :refer :all]))

; Various methods for getting DOIs. Test per-method first, then the top-level function with all inputs.

(def cleanup-doi-inputs [; DOI in various representations
                  ["10.5555/12345678" "10.5555/12345678"]
                  ["doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["dx.doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["http://doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["http://dx.doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["https://doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["https://dx.doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["doi.org/10.5555/12345678" "10.5555/12345678"]
                  ["doi:10.5555/12345678" "10.5555/12345678"]
                  ["DOI:10.5555/12345678" "10.5555/12345678"]
                  ["doi: 10.5555/12345678" "10.5555/12345678"]
                  ["DOI: 10.5555/12345678" "10.5555/12345678"]])

(deftest cleanup-doi-test
  (testing "cleanup-doi works with DOIs in various formats"
    (doseq [[input expected] cleanup-doi-inputs]
      (is (= (cleanup-doi input) expected)))))


(def get-doi-from-get-params-inputs [; DOI embedded in a publisher URL as a query string.
                 [ "http://journals.plos.org/plosone/article?id=10.5555/12345678" "10.5555/12345678"]])

(deftest get-doi-from-get-params-test
  (testing "get-toi-from-get-params is able to extract the DOI from the URL parameters"
    (doseq [[input expected] get-doi-from-get-params-inputs]
      (is (= (get-doi-from-get-params input) expected)))))

(def resolve-url-inputs [;["http://www.bmj.com/content/351/bmj.h6326" "10.1136/bmj.h6326"]
                         ["http://journals.plos.org/plosone/article?id=10.1371/journal.pone.0144297" "10.1371/journal.pone.0144297"]
                         ;["http://www.hindawi.com/journals/aan/2015/708915/" "10.1155/2015/708915"]
                         ])
(deftest resolve-doi-from-url-test
  (testing "resolve-doi-from-url is able to retreive the DOI for the page"
    (doseq [[input expected] resolve-url-inputs]
      (is (= (resolve-doi-from-url input) expected)))))


; The above tests specific types of inputs for each function. 
; Now test that the top-level function is able to choose the appropriate method.
(deftest top-level-lookup-test
  (testing "lookup-uncached uses the best method to extract the DOI"
    (doseq [[input expected] (concat cleanup-doi-inputs get-doi-from-get-params-inputs resolve-url-inputs)]
      (is (= (lookup-uncached input) expected)))))



