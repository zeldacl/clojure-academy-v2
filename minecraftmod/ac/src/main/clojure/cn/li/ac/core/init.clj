(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.ability.adapters.runtime-bridge :as ability-runtime]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.registry.event :as ability-event]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.runtime-container :as ability-runtime-container]
            [cn.li.ac.ability.messages :as ability-messages]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.edn-catalog :as edn-catalog]
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

(defn- apply-mag-manip-collision-damage!
  "Apply the original MagManipEntityBlock's hardcoded raw 10 damage through
  the same calc-event, global/skill scaling, PvP, and attacker attribution
  path as AbilityContext.attack."
  [world-id attacker-uuid target-uuid raw-damage]
  (let [event-damage (ability-event/fire-calc-event!
                       ability-event/CALC-SKILL-ATTACK
                       raw-damage
                       {:player-id attacker-uuid
                        :target-id target-uuid
                        :skill-id :mag-manip})
        final-damage (skill-effects/scale-damage
                       (skill-registry/get-skill :mag-manip)
                       event-damage)]
    (if (pos? final-damage)
      (boolean
        (damage-effects/apply-direct-damage!
          world-id target-uuid final-damage :skill
          {:attacker-uuid attacker-uuid
           :skill-id :mag-manip}))
      true)))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (modid/install-modid!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  ;; The EDN catalog is authoritative for migrated abilities.  No legacy
  ;; catalog fallback is consulted when a skill is pending migration.
  (edn-catalog/initialize!)
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
    :mag-manip-damage
    apply-mag-manip-collision-damage!)
  (block-effects/install-destroy-gate! ability-config/destroy-blocks-enabled?)
  (tutorial-events/register-platform-handlers!)
  (ability-runtime/install-runtime-hooks!
    (ability-runtime-container/create-ability-runtime-container))
  nil)
