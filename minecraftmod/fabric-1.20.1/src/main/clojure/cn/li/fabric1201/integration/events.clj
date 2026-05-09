(ns cn.li.fabric1201.integration.events
  "Fabric 1.20.1 event handlers"
  (:require [cn.li.ac.core :as core]
            [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mc1201.integration.event-feedback :as event-feedback]
            [cn.li.mc1201.integration.event-handlers :as event-handlers]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.fabric1201.gui.registry-impl :as gui-registry-impl])
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
           [net.fabricmc.fabric.api.loot.v2 LootTableEvents$Modify]
           [net.minecraft.world InteractionResult]
           [net.minecraft.server.level ServerPlayer]
           [cn.li.fabric1201.loot FabricLootInjectionHelper]))

(defonce ^:private events-registered? (atom false))

(defn- is-gui-result?
  "Fabric predicate to detect GUI opening results"
  [ret]
  (interaction-result/gui-open-result? ret))

(defn- open-gui-for-result
  "Fabric GUI opener from event result"
  [gui-id player world _pos tile-entity]
  (when (and tile-entity (not (.isClientSide world)))
    (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))

(defn- runtime-activated?
  [player]
  (boolean (get-in (power-runtime/get-player-state (str (.getUUID player)))
                   [:resource-data :activated])))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (event-handlers/handle-block-right-click
    event-data
    dispatcher/on-block-right-click
    is-gui-result?
    open-gui-for-result
    ""))

(defn handle-use-block
  "Handle Fabric UseBlockCallback event"
  [player world hand hit-result]
  (try
    (if (runtime-activated? player)
      InteractionResult/FAIL
      (let [pos (.getBlockPos hit-result)
            block-state (.getBlockState world pos)
            item-stack (.getStackInHand player hand)
            ret (handle-right-click
                  {:x (.getX pos)
                   :y (.getY pos)
                   :z (.getZ pos)
                   :pos pos
                   :sneaking (.isShiftKeyDown player)
                   :player player
                   :hand hand
                   :item-stack item-stack
                   :world world
                   :block (.getBlock block-state)})]
        (if (interaction-result/interaction-consumed? ret)
          InteractionResult/SUCCESS
          InteractionResult/PASS)))
    (catch Throwable t
      (log/info "Error handling use block event:" (.getMessage t))
      (.printStackTrace t)
      InteractionResult/PASS)))

(defn handle-attack-block
  "Handle Fabric AttackBlockCallback event. Mirrors Forge runtime-mode deny behavior."
  [player _world _hand _pos _direction]
  (try
    (if (runtime-activated? player)
      InteractionResult/FAIL
      InteractionResult/PASS)
    (catch Throwable t
      (log/error "Error handling fabric attack block:" (.getMessage t))
      InteractionResult/PASS)))

(defn handle-block-break
  "Handle Fabric block break callback. Return true to continue vanilla break."
  [world player pos state _be]
  (try
    (if (runtime-activated? player)
      false
      (let [block (.getBlock state)
            block-id (event-metadata/identify-block-from-full-name (str block))]
        (if-not block-id
          true
          (let [ret (core/on-block-break
                      {:x (.getX pos)
                       :y (.getY pos)
                       :z (.getZ pos)
                       :pos pos
                       :player player
                       :world world
                       :block block
                       :block-id block-id})]
            (not (and (map? ret) (:cancel-break? ret)))))))
    (catch Throwable t
      (log/error "Error handling fabric block break:" (.getMessage t))
      true)))

(defn handle-block-place-mixin
  "Handle Fabric block placement from BlockItem mixin.
   Returns true when placement should be canceled."
  [player world pos block]
  (try
    (if (and player (runtime-activated? player))
      true
      (let [block-id (event-metadata/identify-block-from-full-name (str block))]
        (if-not block-id
          false
          (let [ret (dispatcher/on-block-place
                      {:x (.getX pos)
                       :y (.getY pos)
                       :z (.getZ pos)
                       :pos pos
                       :player player
                       :world world
                       :block block
                       :block-id block-id})]
            (boolean (and (map? ret) (:cancel-place? ret)))))))
    (catch Throwable t
      (log/error "Error handling fabric block place mixin event:" (.getMessage t))
      false)))

(defn handle-loot-table-modify
  "Inject DSL-defined loot entries into target loot tables at load time."
  [id table-builder]
  (try
    (let [table-id (str id)
          injections (registry-metadata/get-loot-injections-for-table table-id)]
      (when (seq injections)
        (doseq [spec injections]
          (FabricLootInjectionHelper/addItemInjection
            table-builder
            (:item-id spec)
            (int (or (:weight spec) 1))
            (int (or (:quality spec) 0))
            (float (or (:min-count spec) 1.0))
            (float (or (:max-count spec) 1.0))))))
    (catch Throwable t
      (log/error "Error handling loot table modify event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-login
  "Handle Fabric server player join event."
  [^ServerPlayer player]
  (try
    (power-runtime/on-player-login! (str (.getUUID player)))
    (catch Throwable t
      (log/error "Error handling player login event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-logout
  "Handle Fabric server player disconnect event."
  [^ServerPlayer player]
  (try
    (power-runtime/on-player-logout! (str (.getUUID player)))
    (catch Throwable t
      (log/error "Error handling player logout event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-clone
  "Handle Fabric player copy event. Mirrors Forge non-death clone behavior."
  [^ServerPlayer old-player ^ServerPlayer new-player alive]
  (try
    (when alive
      (power-runtime/on-player-clone! (str (.getUUID old-player))
                                      (str (.getUUID new-player))))
    (catch Throwable t
      (log/error "Error handling player clone event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-death
  "Handle Fabric player death event."
  [entity]
  (try
    (when (instance? ServerPlayer entity)
      (power-runtime/on-player-death! (str (.getUUID ^ServerPlayer entity))))
    (catch Throwable t
      (log/error "Error handling player death event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-tick
  "Handle Fabric end-of-server-tick player lifecycle callbacks."
  [server]
  (try
    (doseq [^ServerPlayer player (.getPlayers (.getPlayerList server))]
      (power-runtime/on-player-tick! (str (.getUUID player))))
    (catch Throwable t
      (log/error "Error handling player tick event:" (.getMessage t))
      (.printStackTrace t))))

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
