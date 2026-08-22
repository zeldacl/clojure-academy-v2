(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.ability.adapters.runtime-bridge :as ability-runtime]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.runtime-container :as ability-runtime-container]
            [cn.li.ac.ability.messages :as ability-messages]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.combat.platform :as combat-platform]
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

(defn- apply-block-body-impact!
  "Generic collision damage for configured scripted block bodies."
  [world-id attacker-uuid target-uuid raw-damage]
  (boolean
    (damage-effects/apply-direct-damage!
      world-id target-uuid (double raw-damage) :skill
      {:attacker-uuid attacker-uuid})))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (modid/install-modid!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  ;; Capabilities must be registered before the EDN catalog loads (Design E
  ;; precondition R9) -- capability-aware load-time validation can only see
  ;; what's already registered at the moment it runs. World-facing
  ;; capabilities are entirely Combat Core's own; AC only links its own
  ;; domain ports (resources/progression/energy/marks) after.
  (combat-platform/install!)
  (combat-runtime/install-ac-host-capabilities!)
  ;; The EDN catalog is authoritative for migrated abilities.  No legacy
  ;; catalog fallback is consulted when a skill is pending migration.
  (combat-catalog/initialize!)
  (ability-messages/install!)
  (entity-hook-catalog/install-resolvers!)
  (block-bridge/install-blockstate-hooks!)
  (command-bridge/install-command-hooks!)
  (wireless-world/init-world-data!)
  ;; Register and compile the new neutral combat catalog before runtime
  ;; content activation.  The registry freezes inside initialize!.
  (config-registry/init-configs!)
  ;; Global "Enable PvP" / "Destroy blocks" settings gates — matching upstream
  ;; AbilityPipeline.canAttackPlayer()/canBreakBlock(), consulted by every
  ;; ability effect via the shared mcmod entity-damage/block-manipulation
  ;; primitives instead of each skill file checking config itself.
  (damage-effects/install-pvp-gate! ability-config/attack-player-enabled?)
  (damage-effects/install-scripted-block-body-hit-handler!
    :block-body-impact
    apply-block-body-impact!)
  (block-effects/install-destroy-gate! ability-config/destroy-blocks-enabled?)
  (tutorial-events/register-platform-handlers!)
  (ability-runtime/install-runtime-hooks!
    (ability-runtime-container/create-ability-runtime-container))
  nil)
