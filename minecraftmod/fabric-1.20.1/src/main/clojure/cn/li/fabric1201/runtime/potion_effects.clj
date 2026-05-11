(ns cn.li.fabric1201.runtime.potion-effects
  "Fabric thin adapter for IPotionEffects protocol.
  Delegates to mc1201 potion-effects-core using Fabric server-context."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mc1201.runtime.potion-effects-core :as pec]
            [cn.li.fabric1201.runtime.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-potion-effects []
  (reify ppe/IPotionEffects
    (apply-potion-effect! [_ player-uuid effect-type duration amplifier]
      (pec/apply-potion-effect! (server-ctx/get-server) player-uuid effect-type duration amplifier))
    (remove-potion-effect! [_ player-uuid effect-type]
      (pec/remove-potion-effect! (server-ctx/get-server) player-uuid effect-type))
    (has-potion-effect? [_ player-uuid effect-type]
      (pec/has-potion-effect? (server-ctx/get-server) player-uuid effect-type))
    (clear-all-effects! [_ player-uuid]
      (pec/clear-all-effects! (server-ctx/get-server) player-uuid))))

(defn install-potion-effects! []
  (alter-var-root #'ppe/*potion-effects*
                  (constantly (fabric-potion-effects)))
  (log/info "Fabric potion effects installed"))
