(ns cn.li.forge1201.runtime.potion-effects
  "Forge thin adapter for IPotionEffects protocol.
  Delegates all logic to mc1201 potion-effects-core."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.potion-effects-core :as pec]
            [cn.li.forge1201.runtime.server-context :as server-context]))

(defn forge-potion-effects []
  (pec/create-potion-effects server-context/get-server))

(defn install-potion-effects! []
  (adapter-support/install-adapter! #'ppe/*potion-effects*
                                    (forge-potion-effects)
                                    "Forge potion effects"))
