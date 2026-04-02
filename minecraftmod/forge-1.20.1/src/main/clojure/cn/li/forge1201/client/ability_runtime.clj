(ns cn.li.forge1201.client.ability-runtime
  "CLIENT-ONLY ability input runtime (minimal runnable loop)."
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.forge1201.ability.network :as ability-net]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private local-contexts (atom {}))

(defn active-contexts [] @local-contexts)

(defn on-key-down!
  [player-uuid skill-id]
  (let [c (ctx/new-context player-uuid skill-id)]
    (ctx/register-context! c)
    (swap! local-contexts assoc (:id c) c)
    (ability-net/send-to-server! catalog/MSG-CTX-BEGIN-LINK
                                 {:ctx-id (:id c) :skill-id skill-id})
    c))

(defn on-key-tick!
  [ctx-id]
  (when-let [c (get @local-contexts ctx-id)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-TICK
                                 {:ctx-id ctx-id :skill-id (:skill-id c)})))

(defn on-key-up!
  [ctx-id]
  (when (get @local-contexts ctx-id)
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-UP {:ctx-id ctx-id})
    (ctx/terminate-context! ctx-id nil)
    (swap! local-contexts dissoc ctx-id)))

(defn abort-all!
  []
  (doseq [ctx-id (keys @local-contexts)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
    (ctx/terminate-context! ctx-id nil))
  (reset! local-contexts {}))

(defn init! []
  (log/info "Ability client runtime initialized"))
