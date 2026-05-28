(ns cn.li.forge1201.achievement.bridge
  "Forge bridge for AC achievement-trigger events.

  This bridge is intentionally generic:
  - input: {uuid achievement-id}
  - output: ModCustomTrigger.trigger(ServerPlayer, String)"
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.trigger ModTriggers]
           [java.util UUID]
           [net.minecraftforge.server ServerLifecycleHooks]))

(def ^:private install-guard-lock
  (Object.))

(def ^:private ^:dynamic *installed?*
  false)

(defn- resolve-player
  [uuid-str]
  (when-let [server (ServerLifecycleHooks/getCurrentServer)]
    (let [player-list (.getPlayerList server)]
      (.getPlayer player-list (UUID/fromString (str uuid-str))))))

(defn init!
  []
  (when-not (var-get #'*installed?*)
    (locking install-guard-lock
      (when-not (var-get #'*installed?*)
        (power-runtime/subscribe-achievement-trigger!
          (fn [{:keys [uuid achievement-id]}]
            (try
              (when-let [player (resolve-player uuid)]
                (.trigger ModTriggers/CUSTOM player (str achievement-id)))
              (catch Exception e
                (log/warn "Failed to dispatch achievement trigger" achievement-id (ex-message e))))))
        (alter-var-root #'*installed?* (constantly true))
        (log/info "Forge achievement bridge initialized")))))

