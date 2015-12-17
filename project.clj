(defproject member-domains "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [crossref-util "0.1.9"]
                 [camel-snake-kebab "0.3.2"]
                 [clj-http "2.0.0"]
                 [clj-time "0.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [http-kit "2.1.18"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [korma "0.4.0"]
                 [mysql-java "5.1.21"]
                 [robert/bruce "0.8.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.csv "0.1.3"]
                 [compojure "1.4.0"]
                 [liberator "0.12.2"]
                 [crossref/heartbeat "0.1.2"]
                 [enlive "1.1.6"]
                 [com.cemerick/url "0.1.1"]
                 
                 [selmer "0.9.5"]]
  
  :main ^:skip-aot member-domains.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
