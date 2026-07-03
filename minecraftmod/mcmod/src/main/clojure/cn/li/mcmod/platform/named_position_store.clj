(ns cn.li.mcmod.platform.named-position-store
  "Named position storage via Framework function map.

   Impl stored at [:platform :named-position-store]."
  (:require [cn.li.mcmod.framework :as fw]))

(def named-position-store-keys
  #{:save-location! :delete-location! :get-location
    :list-locations :get-location-count :has-location?})

(defn install-named-position-store!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :named-position-store] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :named-position-store])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :named-position-store]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn save-location!*     [player-uuid location-name world-id x y z]  (call :save-location! player-uuid location-name world-id x y z))
(defn delete-location!*   [player-uuid location-name]                (call :delete-location! player-uuid location-name))
(defn get-location*       [player-uuid location-name]                (call :get-location player-uuid location-name))
(defn list-locations*     [player-uuid]                              (call :list-locations player-uuid))
(defn get-location-count* [player-uuid]                              (call :get-location-count player-uuid))
(defn has-location?*      [player-uuid location-name]                (call :has-location? player-uuid location-name))
