(ns cn.li.fabric1201.config.gameplay-init
  "Initialize gameplay config bridge binding for Fabric.

  This namespace binds the Fabric config-backed gameplay config
  to the ac config namespace via dynamic var injection.

  Unlike Forge's ForgeConfigSpec, Fabric uses JSON-based configuration
  through fabric.mod.json and custom loaders."
  (:require [cn.li.fabric1201.config.gameplay-bridge :as bridge]
            [cn.li.mc1201.config.gameplay-init :as shared-init]
            [cn.li.mcmod.util.log :as log]))

(defn bind-gameplay-config!
  "Bind the Fabric gameplay config bridge to ac config namespace.

  This should be called during mod initialization to make
  Fabric config values available to game logic."
  []
  (try
    (let [config-bridge {:analysis-enabled?           bridge/analysis-enabled?
                         :attack-player?              bridge/attack-player?
                         :destroy-blocks?             bridge/destroy-blocks?
                         :gen-ores?                   bridge/gen-ores?
                         :gen-phase-liquid?           bridge/gen-phase-liquid?
                         :heads-or-tails?             bridge/heads-or-tails?

                         :get-normal-metal-blocks     bridge/get-normal-metal-blocks
                         :get-weak-metal-blocks       bridge/get-weak-metal-blocks
                         :get-metal-entities          bridge/get-metal-entities
                         :is-normal-metal-block?      bridge/is-normal-metal-block?
                         :is-weak-metal-block?        bridge/is-weak-metal-block?
                         :is-metal-block?             bridge/is-metal-block?
                         :is-metal-entity?            bridge/is-metal-entity?

                         :get-cp-recover-cooldown     bridge/get-cp-recover-cooldown
                         :get-cp-recover-speed        bridge/get-cp-recover-speed
                         :get-overload-recover-cooldown bridge/get-overload-recover-cooldown
                         :get-overload-recover-speed  bridge/get-overload-recover-speed
                         :get-init-cp                 bridge/get-init-cp
                         :get-add-cp                  bridge/get-add-cp
                         :get-init-overload           bridge/get-init-overload
                         :get-add-overload            bridge/get-add-overload

                         :get-damage-scale            bridge/get-damage-scale}]
      (shared-init/bind-gameplay-config! config-bridge)
      (log/info "Gameplay config bridge bound successfully (Fabric)"))

    (catch Exception e
      (log/error "Failed to bind gameplay config bridge (Fabric)" e))))
