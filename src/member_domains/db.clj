(ns member-domains.db
  (:require [config.core :refer [env]])
  (:require [korma.db :as kdb])
  (:require [korma.core :as k])
  (:require [korma.db :refer [mysql with-db defdb]])
  (:require [clj-time.coerce :as coerce])
  (:require [clojure.data.json :as json])
  (:require [clojure.java.io :refer [reader]])
  (:require [camel-snake-kebab.core :refer [->kebab-case ->snake_case]])
  (:require [korma.sql.engine :as korma-engine]
            [korma.core :refer :all]))

(defdb db
  (mysql {:user (:database-username env)
          :password (:database-password env)
          :db (:database-name env)
          :naming {:keys ->kebab-case
          :fields ->snake_case}}))

(k/defentity member-domains
  (k/table "member_domains")
  (k/pk :id)
  (k/entity-fields
    ["member_id" :member-id]
    :ignored
    :domain))

(k/defentity member-dois
  (k/table "member_dois")
  (k/pk :id)
  (k/entity-fields
    ["member_id" :member-id]
    :doi
    :resolved))

(k/defentity member-prefixes
  (k/table "member_prefixes")
  (k/pk :id)
  (k/entity-fields
    ["member_id" :member-id]
    ["member_prefix" :prefix]))

(defn unique-member-domains []
  (map :domain (k/select "member_domains"
                           (modifier "DISTINCT")
                           (k/fields :domain)
                           (k/where {:ignored false}))))

(defn num-dois []
  (-> (k/exec-raw ["select count(*) as c from member_dois" []] :results) first :c))

(defn num-resolved-dois []
  (-> (k/exec-raw ["select count(*) as c from member_dois where resolved = true" []] :results) first :c))

(defn num-unresolved-dois []
  (-> (k/exec-raw ["select count(*) as c from member_dois where resolved = false" []] :results) first :c))

(defn num-domains []
  (-> (k/exec-raw ["select count(distinct(domain)) as c from member_domains" []] :results) first :c))

(defn average-dois-per-member
  []
  (let [members-counts (k/exec-raw ["select member_id, count(*) as cnt from member_dois where resolved = true group by member_id" []] :results)
        counts (map :cnt members-counts)
        average-per-member (when (not-empty counts) (/ (apply + counts) (count counts)))]
    (int (or average-per-member 0))))

(defn num-members
  []
  (-> (k/exec-raw ["select count(distinct(member_id)) as cnt from member_dois" []] :results) first :cnt))

(defn ensure-doi [member-id doi]
  (k/exec-raw ["insert ignore into member_dois (member_id, doi) values (?, ?)" [member-id doi]]))

(defn get-resolution-batch
  "Fetch a batch of DOIs to resolve. One DOI per member id."
  []
  (k/exec-raw ["select distinct(member_id) as uniq_member_id, (select doi from member_dois where member_id = uniq_member_id and resolved = false limit 1) as doi from member_dois as members_outer" []] :results))

(defn ensure-domain [member-id domain]
  (k/exec-raw ["insert ignore into member_domains (member_id, domain) values (?, ?)" [member-id domain]]))

(defn ensure-member-prefix [member-id prefix]
  (k/exec-raw ["insert ignore into member_prefixes (member_id, member_prefix) values (?, ?)" [member-id prefix]]))

(defn all-prefixes []
  (map :prefix (k/exec-raw ["select distinct(member_prefix) as prefix from member_prefixes" []] :results)))

(defn mark-doi-resolved [doi]
  (k/update member-dois (k/where {:doi doi}) (k/set-fields {:resolved true})))

(defn update-doi-urls [doi first-url last-url]
  (k/update member-dois (k/where {:doi doi}) (k/set-fields {:first-url first-url :last-url last-url})))

(defn heartbeat []
  ; This will either work or fail.
  (k/select member-domains (k/limit 0)))