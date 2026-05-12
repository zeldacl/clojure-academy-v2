(ns cn.li.forge1201.runtime.potion-effects
  "Forge thin adapter for IPotionEffects protocol.
  Delegates all logic to mc1201 potion-effects-core."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mc1201.runtime.potion-effects-core :as pec]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-potion-effects []
  (pec/create-potion-effects #(ServerLifecycleHooks/getCurrentServer)))

(defn install-potion-effects! []
  (alter-var-root #'ppe/*potion-effects*
                  (constantly (forge-potion-effects)))
  (log/info "Forge potion effects installed"))
