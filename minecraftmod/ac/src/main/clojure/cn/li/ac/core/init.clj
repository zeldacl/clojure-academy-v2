(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.ability.adapters.runtime-bridge :as ability-runtime]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.runtime-container :as ability-runtime-container]
            [cn.li.ac.ability.messages :as ability-messages]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.combat.platform :as combat-platform]
            [cn.li.ability.combat :as ability-combat]
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
  "Route a scripted block-body collision into its owning ability's own EDN
   program instead of applying a flat platform-side damage number -- the
   single combat execution path requirement means even a passive world
   collision (not a direct player intent) must go through Combat Core's
   dispatch, so the hit amount, reactions and VFX all come from mag-manip's
   own :throw-damage tunable, not from this callback.

   `raw-damage` (the entity's registered spec damage, still used only as the
   Java-side \"is this body configured to deal damage at all\" gate before
   this callback ever fires) is intentionally unused here."
  [world-id attacker-uuid target-uuid _raw-damage]
  (when (combat-catalog/available? :mag-manip)
    (= :accepted
       (:status (combat-runtime/dispatch-event!
                 attacker-uuid :mag-manip :block-body-hit
                 {:world-id world-id :target-id target-uuid})))))


(defn- world-whitelisted-for-destroy?
  "Honor the legacy configuration's dimension exemptions while keeping the
   decision at the shared block-effect boundary used by the final runtime."
  [world-id]
  (when (seq world-id)
    (let [entries (set (ability-config/whitelisted-destroying-worlds))
          numeric-id (case world-id
                       "minecraft:overworld" "0"
                       "minecraft:the_nether" "-1"
                       "minecraft:the_end" "1"
                       nil)]
      (boolean (or (contains? entries world-id)
                   (and numeric-id (contains? entries numeric-id)))))))
(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (modid/install-modid!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  ;; Capabilities must be registered before the EDN catalog loads (Design E
  ;; precondition R9) -- capability-aware load-time validation can only see
  ;; what's already registered at the moment it runs. World-facing
  ;; capabilities are entirely Combat Core's own; AC only links its own
  (ability-combat/install-runtime!
    (ability-combat/create-runtime
     {:execute-result! (fn [owner result]
                         (when (= :applied (:status result))
                           (combat-runtime/finalize-result! owner result))) }))
  (combat-platform/install!
   {:schedule-beam! ability-combat/schedule-installed!})
  (combat-runtime/install-ac-host-capabilities!)
  (combat-runtime/initialize-final-runtime!)
  ;; The final EDN catalog is authoritative.  Catalog initialization fails
  ;; closed if any source is not a compiled final program.
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
  (block-effects/install-destroy-gate!
    (fn [world-id]
      (or (ability-config/destroy-blocks-enabled?)
          (world-whitelisted-for-destroy? world-id))))
  (tutorial-events/register-platform-handlers!)
  (ability-runtime/install-runtime-hooks!
    (ability-runtime-container/create-ability-runtime-container))
  nil)



