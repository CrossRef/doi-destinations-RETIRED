(ns member-domains.db
  (:require [crossref.util.config :refer [config]])
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
  (mysql {:user (:database-username config)
          :password (:database-password config)
          :db (:database-name config)
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

(defn unique-member-domains []
  (map :domain (k/select "member_domains"
                           (modifier "DISTINCT")
                           (k/fields :domain)
                           (k/where {:ignored false}))))

(defn ensure-doi [member-id doi]
  (k/exec-raw ["insert ignore into member_dois (member_id, doi) values (?, ?)" [member-id doi]]))

(defn get-resolution-batch
  "Fetch a batch of DOIs to resolve. One DOI per member id."
  []
  (k/exec-raw ["select distinct(member_id) as uniq_member_id, (select doi from member_dois where member_id = uniq_member_id and resolved = false limit 1) as doi from member_dois as members_outer" []] :results))

(defn ensure-domain [member-id domain]
  (k/exec-raw ["insert ignore into member_domains (member_id, domain) values (?, ?)" [member-id domain]]))

(defn mark-doi-resolved [doi]
  (k/update member-dois (k/where {:doi doi}) (k/set-fields {:resolved true})))