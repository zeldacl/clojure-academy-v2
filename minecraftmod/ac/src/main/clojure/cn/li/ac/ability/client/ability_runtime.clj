(ns cn.li.ac.ability.client.ability-runtime
  "AC-side runtime bridge for client key events.

  Keeps AC independent from Minecraft classes while delegating to platform runtime
  when available."
  (:require [cn.li.mcmod.util.log :as log]))

(defn- invoke-runtime
  [sym player-uuid key-idx]
  (if-let [f (resolve sym)]
    (@f player-uuid key-idx)
    (do
      (log/debug "Client runtime function not available:" sym)
      nil)))

(defn on-slot-key-down!
  [player-uuid key-idx]
  (invoke-runtime 'cn.li.forge1201.client.ability-runtime/on-slot-key-down!
                  player-uuid key-idx))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (invoke-runtime 'cn.li.forge1201.client.ability-runtime/on-slot-key-tick!
                  player-uuid key-idx))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (invoke-runtime 'cn.li.forge1201.client.ability-runtime/on-slot-key-up!
                  player-uuid key-idx))
