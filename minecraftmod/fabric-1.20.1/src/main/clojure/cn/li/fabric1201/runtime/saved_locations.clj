(ns cn.li.fabric1201.runtime.saved-locations
  "Fabric thin adapter for ISavedLocations protocol.
  Delegates all MC/NBT logic to mc1201 saved-locations-core; obtains server
  reference from Fabric server-context."
  (:require [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mc1201.runtime.saved-locations-core :as slc]
            [cn.li.fabric1201.adapter.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-saved-locations []
  (slc/create-saved-locations server-ctx/get-server))

(defn install-saved-locations! []
  (alter-var-root #'psl/*saved-locations*
                  (constantly (fabric-saved-locations)))
  (log/info "Fabric saved locations installed"))
