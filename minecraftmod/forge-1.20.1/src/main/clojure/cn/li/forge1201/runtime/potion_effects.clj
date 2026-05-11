(ns cn.li.forge1201.runtime.potion-effects
  "Forge thin adapter for IPotionEffects protocol.
  Delegates all logic to mc1201 potion-effects-core."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mc1201.runtime.potion-effects-core :as pec]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server []
  (ServerLifecycleHooks/getCurrentServer))

(defn forge-potion-effects []
  (reify ppe/IPotionEffects
    (apply-potion-effect! [_ player-uuid effect-type duration amplifier]
      (pec/apply-potion-effect! (get-server) player-uuid effect-type duration amplifier))
    (remove-potion-effect! [_ player-uuid effect-type]
      (pec/remove-potion-effect! (get-server) player-uuid effect-type))
    (has-potion-effect? [_ player-uuid effect-type]
      (pec/has-potion-effect? (get-server) player-uuid effect-type))
    (clear-all-effects! [_ player-uuid]
      (pec/clear-all-effects! (get-server) player-uuid))))

(defn install-potion-effects! []
  (alter-var-root #'ppe/*potion-effects*
                  (constantly (forge-potion-effects)))
  (log/info "Forge potion effects installed"))
