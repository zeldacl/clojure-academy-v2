(ns cn.li.mcmod.platform.named-position-store
  "Policy-free protocol for content-named world-position storage."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol INamedPositionStore
  (save-location! [this player-uuid location-name world-id x y z])
  (delete-location! [this player-uuid location-name])
  (get-location [this player-uuid location-name])
  (list-locations [this player-uuid])
  (get-location-count [this player-uuid])
  (has-location? [this player-uuid location-name]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-named-position-store!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "named-position-store")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* INamedPositionStore
  [save-location!* save-location! player-uuid location-name world-id x y z]
  [delete-location!* delete-location! player-uuid location-name]
  [get-location* get-location player-uuid location-name]
  [list-locations* list-locations player-uuid]
  [get-location-count* get-location-count player-uuid]
  [has-location?* has-location? player-uuid location-name])
