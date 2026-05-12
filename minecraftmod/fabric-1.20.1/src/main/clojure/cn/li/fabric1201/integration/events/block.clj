(ns cn.li.fabric1201.integration.events.block
  "Fabric block break/place handlers extracted from monolithic events namespace."
  (:require [cn.li.ac.core :as core]
            [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.runtime.hooks-core :as power-runtime]))

(defn- runtime-activated?
  [player]
  (boolean (get-in (power-runtime/get-player-state (str (.getUUID player)))
                   [:resource-data :activated])))

(defn handle-block-break
  [world player pos state _be]
  (try
    (if (runtime-activated? player)
      false
      (let [block (.getBlock state)
            block-id (event-metadata/identify-block-from-full-name (str block))]
        (if-not block-id
          true
          (let [ret (core/on-block-break
                      {:x (.getX pos)
                       :y (.getY pos)
                       :z (.getZ pos)
                       :pos pos
                       :player player
                       :world world
                       :block block
                       :block-id block-id})]
            (not (and (map? ret) (:cancel-break? ret)))))))
    (catch Throwable t
      (log/error "Error handling fabric block break:" (.getMessage t))
      true)))

(defn handle-block-place-mixin
  [player world pos block]
  (try
    (if (and player (runtime-activated? player))
      true
      (let [block-id (event-metadata/identify-block-from-full-name (str block))]
        (if-not block-id
          false
          (let [ret (dispatcher/on-block-place
                      {:x (.getX pos)
                       :y (.getY pos)
                       :z (.getZ pos)
                       :pos pos
                       :player player
                       :world world
                       :block block
                       :block-id block-id})]
            (boolean (and (map? ret) (:cancel-place? ret)))))))
    (catch Throwable t
      (log/error "Error handling fabric block place mixin event:" (.getMessage t))
      false)))
