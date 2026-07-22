(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.ability.adapters.runtime-bridge :as ability-runtime]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.runtime-container :as ability-runtime-container]
            [cn.li.ac.ability.messages :as ability-messages]
            [cn.li.ac.block.platform-bridge :as block-bridge]
            [cn.li.ac.command.platform-bridge :as command-bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.config.registry :as config-registry]
            [cn.li.ac.entity.hook-catalog :as entity-hook-catalog]
            [cn.li.ac.tutorial.events :as tutorial-events]
            [cn.li.ac.wireless.data.world :as wireless-world]
            [cn.li.mcmod.platform.block-manipulation :as block-effects]
            [cn.li.mcmod.platform.entity-damage :as damage-effects]
            [cn.li.mcmod.util.log :as log]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (modid/install-modid!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  (ability-messages/install!)
  (entity-hook-catalog/install-resolvers!)
  (block-bridge/install-blockstate-hooks!)
  (command-bridge/install-command-hooks!)
  (wireless-world/init-world-data!)
  (config-registry/init-configs!)
  ;; Global "Enable PvP" / "Destroy blocks" settings gates — matching upstream
  ;; AbilityPipeline.canAttackPlayer()/canBreakBlock(), consulted by every
  ;; ability effect via the shared mcmod entity-damage/block-manipulation
  ;; primitives instead of each skill file checking config itself.
  (damage-effects/install-pvp-gate! ability-config/attack-player-enabled?)
  (block-effects/install-destroy-gate! ability-config/destroy-blocks-enabled?)
  (tutorial-events/register-platform-handlers!)
  (ability-runtime/install-runtime-hooks!
    (ability-runtime-container/create-ability-runtime-container))
  nil)
