(ns cn.li.fabric1201.runtime.potion-effects
  "Fabric thin adapter for IPotionEffects protocol.
  Delegates to mc1201 potion-effects-core using Fabric server-context."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mc1201.runtime.potion-effects-core :as pec]
            [cn.li.fabric1201.adapter.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-potion-effects []
  (pec/create-potion-effects server-ctx/get-server))

(defn install-potion-effects! []
  (alter-var-root #'ppe/*potion-effects*
                  (constantly (fabric-potion-effects)))
  (log/info "Fabric potion effects installed"))
