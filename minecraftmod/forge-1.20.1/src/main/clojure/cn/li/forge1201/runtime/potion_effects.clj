(ns cn.li.forge1201.runtime.potion-effects
  "Forge thin adapter for IPotionEffects protocol.
  Delegates all logic to mc1201 potion-effects-core."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.potion-effects-core :as pec])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-potion-effects []
  (pec/create-potion-effects #(ServerLifecycleHooks/getCurrentServer)))

(defn install-potion-effects! []
  (adapter-support/install-adapter! #'ppe/*potion-effects*
                                    (forge-potion-effects)
                                    "Forge potion effects"))
