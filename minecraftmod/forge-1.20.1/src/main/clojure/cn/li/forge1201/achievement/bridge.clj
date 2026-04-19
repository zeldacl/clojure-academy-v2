(ns cn.li.forge1201.achievement.bridge
  "Forge bridge for AC achievement-trigger events.

  This bridge is intentionally generic:
  - input: {uuid achievement-id}
  - output: ModCustomTrigger.trigger(ServerPlayer, String)"
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.trigger ModTriggers]
           [java.util UUID]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defonce ^:private installed? (atom false))

(defn- resolve-player
  [uuid-str]
  (when-let [server (ServerLifecycleHooks/getCurrentServer)]
    (let [player-list (.getPlayerList server)]
      (.getPlayer player-list (UUID/fromString (str uuid-str))))))

(defn init!
  []
  (when (compare-and-set! installed? false true)
    (evt/subscribe-ability-event!
      evt/EVT-ACHIEVEMENT-TRIGGER
      (fn [{:keys [uuid achievement-id]}]
        (try
          (when-let [player (resolve-player uuid)]
            (.trigger ModTriggers/CUSTOM player (str achievement-id)))
          (catch Exception e
            (log/warn "Failed to dispatch achievement trigger" achievement-id (ex-message e))))))
    (log/info "Forge achievement bridge initialized")))

