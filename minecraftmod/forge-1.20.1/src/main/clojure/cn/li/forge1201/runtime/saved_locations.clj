(ns cn.li.forge1201.runtime.saved-locations
  "Forge thin adapter for ISavedLocations protocol.
  Delegates all MC/NBT logic to mc1201 saved-locations-core; only provides
  the server reference via Forge ServerLifecycleHooks."
  (:require [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mc1201.runtime.saved-locations-core :as slc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server []
  (ServerLifecycleHooks/getCurrentServer))

(defn forge-saved-locations []
  (reify psl/ISavedLocations
    (save-location! [_ player-uuid location-name world-id x y z]
      (slc/save-location! (get-server) player-uuid location-name world-id x y z))
    (delete-location! [_ player-uuid location-name]
      (slc/delete-location! (get-server) player-uuid location-name))
    (get-location [_ player-uuid location-name]
      (slc/get-location (get-server) player-uuid location-name))
    (list-locations [_ player-uuid]
      (slc/list-locations (get-server) player-uuid))
    (get-location-count [_ player-uuid]
      (slc/get-location-count (get-server) player-uuid))
    (has-location? [_ player-uuid location-name]
      (slc/has-location? (get-server) player-uuid location-name))))

(defn install-saved-locations! []
  (alter-var-root #'psl/*saved-locations*
                  (constantly (forge-saved-locations)))
  (log/info "Forge saved locations installed"))
