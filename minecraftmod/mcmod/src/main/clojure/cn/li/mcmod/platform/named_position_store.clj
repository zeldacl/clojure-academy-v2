(ns cn.li.mcmod.platform.named-position-store
  "Policy-free protocol for content-named world-position storage."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol INamedPositionStore
  (save-location! [this player-uuid location-name world-id x y z])
  (delete-location! [this player-uuid location-name])
  (get-location [this player-uuid location-name])
  (list-locations [this player-uuid])
  (get-location-count [this player-uuid])
  (has-location? [this player-uuid location-name]))

(defn install-named-position-store!
  [impl label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :named-position-store] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :named-position-store])))
(defn current [] (get-in @(fw/fw-atom) [:platform :named-position-store]))
(defn call-with-runtime [rt f] (f rt))

(defn save-location!* [player-uuid location-name world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :named-position-store])]
    (save-location! rt player-uuid location-name world-id x y z)))
(defn delete-location!* [player-uuid location-name]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :named-position-store])]
    (delete-location! rt player-uuid location-name)))
(defn get-location* [player-uuid location-name]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :named-position-store])]
    (get-location rt player-uuid location-name)))
(defn list-locations* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :named-position-store])]
    (list-locations rt player-uuid)))
(defn get-location-count* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :named-position-store])]
    (get-location-count rt player-uuid)))
(defn has-location?* [player-uuid location-name]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :named-position-store])]
    (has-location? rt player-uuid location-name)))
