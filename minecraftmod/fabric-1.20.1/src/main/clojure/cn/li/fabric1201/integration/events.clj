(ns cn.li.fabric1201.integration.events
  "Fabric 1.20.1 event handlers"
  (:require [cn.li.fabric1201.integration.events.interact :as interact-events]
            [cn.li.fabric1201.integration.events.block :as block-events]
            [cn.li.fabric1201.integration.events.loot :as loot-events]
            [cn.li.fabric1201.integration.events.lifecycle :as lifecycle-events]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle])
  (:import [net.fabricmc.fabric.api.event.player UseBlockCallback
            AttackBlockCallback
            PlayerBlockBreakEvents$Before]
           [net.fabricmc.fabric.api.event.lifecycle.v1 ServerWorldEvents$Load
            ServerWorldEvents$Unload
            ServerTickEvents$EndTick]
           [net.fabricmc.fabric.api.networking.v1 ServerPlayConnectionEvents$Join
            ServerPlayConnectionEvents$Disconnect]
           [net.fabricmc.fabric.api.entity.event.v1 ServerPlayerEvents$CopyFrom
            ServerLivingEntityEvents$AfterDeath]
           [net.fabricmc.fabric.api.loot.v2 LootTableEvents$Modify]))

(defonce ^:private events-registered? (atom false))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (interact-events/handle-right-click event-data))

(defn handle-use-block
  "Handle Fabric UseBlockCallback event"
  [player world hand hit-result]
  (interact-events/handle-use-block player world hand hit-result))

(defn handle-attack-block
  "Handle Fabric AttackBlockCallback event. Mirrors Forge runtime-mode deny behavior."
  [player _world _hand _pos _direction]
  (interact-events/handle-attack-block player _world _hand _pos _direction))

(defn handle-block-break
  "Handle Fabric block break callback. Return true to continue vanilla break."
  [world player pos state _be]
  (block-events/handle-block-break world player pos state _be))

(defn handle-block-place-mixin
  "Handle Fabric block placement from BlockItem mixin.
   Returns true when placement should be canceled."
  [player world pos block]
  (block-events/handle-block-place-mixin player world pos block))

(defn handle-loot-table-modify
  "Inject DSL-defined loot entries into target loot tables at load time."
  [id table-builder]
  (loot-events/handle-loot-table-modify id table-builder))

(defn handle-player-login
  "Handle Fabric server player join event."
  [player]
  (lifecycle-events/handle-player-login player))

(defn handle-player-logout
  "Handle Fabric server player disconnect event."
  [player]
  (lifecycle-events/handle-player-logout player))

(defn handle-player-clone
  "Handle Fabric player copy event. Mirrors Forge non-death clone behavior."
  [old-player new-player alive]
  (lifecycle-events/handle-player-clone old-player new-player alive))

(defn handle-player-death
  "Handle Fabric player death event."
  [entity]
  (lifecycle-events/handle-player-death entity))

(defn handle-player-tick
  "Handle Fabric end-of-server-tick player lifecycle callbacks."
  [server]
  (lifecycle-events/handle-player-tick server))

(defn register-events
  "Register Fabric event listeners."
  []
  (if-not (compare-and-set! events-registered? false true)
    (log/info "Fabric event listeners already registered, skipping")
    (do
      (log/info "Registering Fabric event listeners...")

      (.register UseBlockCallback/EVENT
                 (reify UseBlockCallback
                   (interact [_ player world hand hit-result]
                     (handle-use-block player world hand hit-result))))

      (.register AttackBlockCallback/EVENT
                 (reify AttackBlockCallback
                   (interact [_ player world hand pos direction]
                     (handle-attack-block player world hand pos direction))))

      (.register net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents/BEFORE
                 (reify PlayerBlockBreakEvents$Before
                   (beforeBlockBreak [_ world player pos state block-entity]
                     (handle-block-break world player pos state block-entity))))

      (.register net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents/LOAD
                 (reify ServerWorldEvents$Load
                   (onWorldLoad [_ _server world]
                     (world-lifecycle/dispatch-world-load world nil))))

      (.register net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents/UNLOAD
                 (reify ServerWorldEvents$Unload
                   (onWorldUnload [_ _server world]
                     (world-lifecycle/dispatch-world-unload world))))

      (.register net.fabricmc.fabric.api.loot.v2.LootTableEvents/MODIFY
                 (reify LootTableEvents$Modify
                   (modifyLootTable [_ _resource-manager _loot-manager id table-builder _source]
                     (handle-loot-table-modify id table-builder))))

      (.register net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents/JOIN
                 (reify ServerPlayConnectionEvents$Join
                   (onPlayReady [_ handler _sender _server]
                     (handle-player-login (.-player handler)))))

      (.register net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents/DISCONNECT
                 (reify ServerPlayConnectionEvents$Disconnect
                   (onPlayDisconnect [_ handler _server]
                     (handle-player-logout (.-player handler)))))

      (.register net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents/COPY_FROM
                 (reify ServerPlayerEvents$CopyFrom
                   (copyFromPlayer [_ old-player new-player alive]
                     (handle-player-clone old-player new-player alive))))

      (.register net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents/AFTER_DEATH
                 (reify ServerLivingEntityEvents$AfterDeath
                   (afterDeath [_ entity _damage-source]
                     (handle-player-death entity))))

      (.register net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents/END_SERVER_TICK
                 (reify ServerTickEvents$EndTick
                   (onEndTick [_ server]
                     (handle-player-tick server))))

      (log/info "Fabric event listeners registered"))))
