(ns cn.li.ac.core.platform-bootstrap
  "AC platform-neutral bootstrap bindings extracted from cn.li.ac.core."
  (:require [cn.li.ac.ability.platform-bridge :as ability-platform-bridge]
            [cn.li.ac.achievement.registry :as achievement-registry]
            [cn.li.ac.block.platform-bridge :as block-platform-bridge]
            [cn.li.ac.command.platform-bridge :as command-platform-bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.config.registry :as config-registry]
            [cn.li.ac.energy.operations :as energy-ops]
            [cn.li.ac.gui.platform-adapter :as platform-gui]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            [cn.li.ac.integration.block.energy-converter.platform-bridge :as energy-platform-bridge]
            [cn.li.ac.integration.platform-bridge :as integration-platform-bridge]
            [cn.li.ac.recipe.crafting-recipes :as crafting-recipes]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.ac.terminal.platform-bridge :as terminal-platform-bridge]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.world :as wd]
            [cn.li.ac.wireless.gui.sync.handler :as network-handler-helpers]
            [cn.li.mcmod.block.network-handler-bridge :as network-handler-bridge]
            [cn.li.mcmod.config :as mcmod-config]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata]
            [cn.li.mcmod.gui.registry-core :as gui-adapter]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.platform.resource :as platform-res]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.energy ItemEnergyApi ItemEnergyApi$Bridge WirelessQueryApi WirelessQueryApi$Bridge]
           [cn.li.acapi.energy.handle BlockPoint EnergyItemHandle NodeConnectionRef WirelessNetworkRef WorldHandle]
           [cn.li.acapi.wireless IWirelessGenerator IWirelessNode IWirelessReceiver]))

(defn bind-mod-id!
  []
  (alter-var-root #'mcmod-config/*mod-id* (constantly modid/MOD-ID))
  (alter-var-root #'platform-res/*resource-location-fn*
                  (constantly (fn [namespace path]
                                (if (nil? namespace)
                                  (mcmod-config/resource-location path)
                                  (mcmod-config/resource-location namespace path)))))
  nil)

(defn register-slot-validators!
  []
  (slot-registry/register-slot-validator! :energy slot-validators/energy-item-validator)
  (slot-registry/register-slot-validator! :plate slot-validators/constraint-plate-validator)
  (slot-registry/register-slot-validator! :core slot-validators/matrix-core-validator)
  (slot-registry/register-slot-validator! :output slot-validators/output-slot-validator)
  nil)

(defn register-gui-platform-impl!
  []
  (gui-adapter/register-gui-platform-impl!
    {:set-client-container! #'platform-gui/set-client-container!
     :clear-client-container! #'platform-gui/clear-client-container!
     :get-client-container #'platform-gui/get-client-container
     :register-active-container! #'platform-gui/register-active-container!
     :unregister-active-container! #'platform-gui/unregister-active-container!
     :register-player-container! #'platform-gui/register-player-container!
     :unregister-player-container! #'platform-gui/unregister-player-container!
     :get-player-container #'platform-gui/get-player-container
     :get-player-container-from-active #'platform-gui/get-player-container-from-active
     :get-container-for-menu #'platform-gui/get-container-for-menu
     :get-container-by-id #'platform-gui/get-container-by-id
     :get-menu-container-id #'platform-gui/get-menu-container-id
     :register-menu-container! #'platform-gui/register-menu-container!
     :unregister-menu-container! #'platform-gui/unregister-menu-container!
     :register-container-by-id! #'platform-gui/register-container-by-id!
     :unregister-container-by-id! #'platform-gui/unregister-container-by-id!
     :safe-tick! #'platform-gui/safe-tick!
     :safe-validate #'platform-gui/safe-validate
     :safe-sync! #'platform-gui/safe-sync!
     :safe-close! #'platform-gui/safe-close!
     :slot-count #'platform-gui/slot-count
     :slot-get-item #'platform-gui/slot-get-item
     :slot-set-item! #'platform-gui/slot-set-item!
     :slot-changed! #'platform-gui/slot-changed!
     :slot-can-place? #'platform-gui/slot-can-place?
     :get-container-type #'platform-gui/get-container-type
     :get-gui-id-for-container #'platform-gui/get-gui-id-for-container
     :get-menu-type #'platform-gui/get-menu-type
     :register-menu-type! #'platform-gui/register-menu-type!})
  nil)

(defn init-world-data!
  []
  (wd/init-world-data!)
  nil)

(defn install-java-api-bridges!
  []
  (ItemEnergyApi/installBridge
    (reify ItemEnergyApi$Bridge
      (isSupported [_ item]
        (boolean (energy-ops/is-energy-item-supported? (.rawStack ^EnergyItemHandle item))))
      (getEnergy [_ item]
        (double (or (energy-ops/get-item-energy (.rawStack ^EnergyItemHandle item)) 0.0)))
      (getMaxEnergy [_ item]
        (double (or (energy-ops/get-item-max-energy (.rawStack ^EnergyItemHandle item)) 0.0)))
      (setEnergy [_ item amt]
        (energy-ops/set-item-energy! (.rawStack ^EnergyItemHandle item) amt))
      (charge [_ item amt ignore-bandwidth]
        (double (energy-ops/charge-energy-to-item (.rawStack ^EnergyItemHandle item) amt ignore-bandwidth)))
      (pull [_ item amt ignore-bandwidth]
        (double (energy-ops/pull-energy-from-item (.rawStack ^EnergyItemHandle item) amt ignore-bandwidth)))
      (getDescription [_ item]
        (format "%.0f/%.0f IF"
                (double (or (energy-ops/get-item-energy (.rawStack ^EnergyItemHandle item)) 0.0))
                (double (or (energy-ops/get-item-max-energy (.rawStack ^EnergyItemHandle item)) 0.0))))))
  (WirelessQueryApi/installBridge
    (reify WirelessQueryApi$Bridge
      (getWirelessNetByMatrix [_ matrix]
        (when-let [net (wireless-api/get-wireless-net-by-matrix matrix)]
          (WirelessNetworkRef/of net)))
      (getWirelessNetByNode [_ node]
        (when-let [net (wireless-api/get-wireless-net-by-node node)]
          (WirelessNetworkRef/of net)))
      (getNetInRange [_ world center range max-results]
        (mapv (fn [net] (WirelessNetworkRef/of net))
              (wireless-api/get-nets-in-range (.rawWorld ^WorldHandle world)
                                              (.x ^BlockPoint center)
                                              (.y ^BlockPoint center)
                                              (.z ^BlockPoint center)
                                              range
                                              max-results)))
      (getNodeConnByNode [_ node]
        (when-let [conn (wireless-api/get-node-conn-by-node node)]
          (NodeConnectionRef/of conn)))
      (getNodeConnByUser [_ user]
        (cond
          (instance? IWirelessGenerator user)
          (when-let [conn (wireless-api/get-node-conn-by-generator user)]
            (NodeConnectionRef/of conn))
          (instance? IWirelessReceiver user)
          (when-let [conn (wireless-api/get-node-conn-by-receiver user)]
            (NodeConnectionRef/of conn))
          (instance? IWirelessNode user)
          (when-let [conn (wireless-api/get-node-conn-by-node user)]
            (NodeConnectionRef/of conn))
          :else nil))
      (getNodesInRange [_ world center]
        (wireless-api/get-nodes-in-range-at (.rawWorld ^WorldHandle world)
                                            (.x ^BlockPoint center)
                                            (.y ^BlockPoint center)
                                            (.z ^BlockPoint center)))))
  (log/info "Installed AC Java API bridges")
  nil)

(defn install-platform-bridges!
  []
  (ability-platform-bridge/install-ability-runtime-hooks!)
  (block-platform-bridge/install-blockstate-hooks!)
  (command-platform-bridge/install-command-hooks!)
  (integration-platform-bridge/install-integration-hooks!)
  (energy-platform-bridge/install-energy-integration-hooks!)
  (terminal-platform-bridge/install-terminal-ui-hooks!)
  nil)

(defn register-network-helper-fns!
  []
  (network-handler-bridge/register-helper-fns!
    {:get-world network-handler-helpers/get-world
     :get-tile-at network-handler-helpers/get-tile-at})
  nil)

(defn register-datagen-metadata!
  []
  (datagen-metadata/register-achievement-data!
    {:tabs (achievement-registry/all-tabs)
     :all-achievements (achievement-registry/all-achievements)
     :translation-maps (achievement-registry/translation-maps)})
  (datagen-metadata/register-recipes! (crafting-recipes/get-all-recipes))
  (datagen-metadata/register-item-overlay-fn! :matter-unit special-items/matter-unit-overlay-data)
  nil)

(defn init-configs!
  []
  (config-registry/init-configs!)
  nil)
