(remove-ns 'cn.li.fabric1201.integration.events.block)

(ns cn.li.fabric1201.integration.events.block
  "Fabric block break/place handlers extracted from monolithic events namespace."
  (:require [cn.li.mc1201.integration.event-support :as event-support]
            [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.hooks.core :as power-runtime]))

(defn- runtime-activated?
  [player]
  (boolean (get-in (power-runtime/get-player-state (str (.getUUID player)))
                   [:resource-data :activated])))

(defn handle-block-break
  [world player pos state _be]
  (event-support/guarded-call
    "fabric block break"
    true
    (fn []
      (if (runtime-activated? player)
        false
        (let [block (.getBlock state)
              block-id (event-metadata/identify-block-from-full-name (str block))]
          (if-not block-id
            true
            (let [ret (dispatcher/on-block-break
                        {:x (.getX pos)
                         :y (.getY pos)
                         :z (.getZ pos)
                         :pos pos
                         :player player
                         :world world
                         :block block
                         :block-id block-id})]
              (not (and (map? ret) (:cancel-break? ret))))))))))

(defn handle-block-place-mixin
  [player world pos block]
  (event-support/guarded-call
    "fabric block place"
    false
    (fn []
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
              (boolean (and (map? ret) (:cancel-place? ret))))))))))
