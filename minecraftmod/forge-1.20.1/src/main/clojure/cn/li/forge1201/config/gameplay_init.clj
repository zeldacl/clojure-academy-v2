(ns cn.li.forge1201.config.gameplay-init
  "Initialize gameplay config bridge binding.

  This namespace binds the ForgeConfigSpec-backed gameplay config
  to the ac config namespace via dynamic var injection."
  (:require [cn.li.forge1201.config.gameplay-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]))

(defn bind-gameplay-config!
  "Bind the gameplay config bridge to ac config namespace.

  This should be called during FMLCommonSetupEvent to make
  ForgeConfigSpec values available to game logic."
  []
  (try
    ;; Require the ac config namespace
    (require 'cn.li.ac.config.gameplay)

    ;; Create the bridge map with all config access functions
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

                         :get-damage-scale            bridge/get-damage-scale}

          ;; Get the dynamic var from ac config namespace
          config-var (ns-resolve 'cn.li.ac.config.gameplay '*config-bridge*)]

      ;; Bind the config bridge
      (when config-var
        (alter-var-root config-var (constantly config-bridge))
        (log/info "Gameplay config bridge bound successfully")))

    (catch Exception e
      (log/error "Failed to bind gameplay config bridge" e))))
