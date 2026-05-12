(ns cn.li.forge1201.runtime.saved-locations
  "Forge thin adapter for ISavedLocations protocol.
  Delegates all MC/NBT logic to mc1201 saved-locations-core; only provides
  the server reference via Forge ServerLifecycleHooks."
  (:require [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.saved-locations-core :as slc]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mcmod.util.log :as log]))

(defn forge-saved-locations []
  (slc/create-saved-locations server-context/get-server))

(defn install-saved-locations! []
  (adapter-support/install-adapter! #'psl/*saved-locations*
                                    (forge-saved-locations)
                                    "Forge saved locations"))
