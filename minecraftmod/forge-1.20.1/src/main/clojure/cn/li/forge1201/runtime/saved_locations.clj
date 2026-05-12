(ns cn.li.forge1201.runtime.saved-locations
  "Forge thin adapter for ISavedLocations protocol.
  Delegates all MC/NBT logic to mc1201 saved-locations-core; only provides
  the server reference via Forge ServerLifecycleHooks."
  (:require [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mc1201.runtime.saved-locations-core :as slc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-saved-locations []
  (slc/create-saved-locations #(ServerLifecycleHooks/getCurrentServer)))

(defn install-saved-locations! []
  (alter-var-root #'psl/*saved-locations*
                  (constantly (forge-saved-locations)))
  (log/info "Forge saved locations installed"))
