(ns cn.li.forge1201.integration.tutorial-events
  "Forge event listeners for tutorial condition-based unlock.

  Listens to ItemCraftedEvent, EntityItemPickupEvent, ItemSmeltedEvent
  and dispatches matching condition checks through mcmod platform hooks.

  Uses the same MinecraftForge/EVENT_BUS pattern as item_handler.clj."
  (:require [cn.li.mcmod.platform.tutorial-events :as tutorial-platform]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerEvent$ItemCraftedEvent
                                                 PlayerEvent$ItemSmeltedEvent]
           [net.minecraftforge.event.entity.player EntityItemPickupEvent]
           [net.minecraftforge.event TickEvent$PlayerTickEvent TickEvent$Phase]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.client Minecraft]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]))

;; ============================================================================
;; Item ID resolution
;; ============================================================================

(defn- item-stack->id
  "Get the runtime item id (\"namespace:path\") from an ItemStack."
  [^ItemStack stack]
  (when stack
    (try
      (let [item (.getItem stack)
            ^ResourceLocation key (.getKey BuiltInRegistries/ITEM item)]
        (when key
          (str (.getNamespace key) ":" (.getPath key))))
      (catch Exception _
        nil))))

;; ============================================================================
;; Event dispatch helpers
;; ============================================================================

(defn- dispatch-item-event!
  [^Player player item-id event-type]
  (when (and player item-id)
    (try
      (tutorial-platform/on-item-event! (str (.getUUID player)) item-id event-type)
      (catch Throwable _))))

(defn- on-item-crafted
  [^PlayerEvent$ItemCraftedEvent event]
  (when-let [player (.getEntity event)]
    (when (instance? Player player)
      (let [item-id (item-stack->id (.getCrafting event))]
        (dispatch-item-event! ^Player player item-id :item-crafted)))))

(defn- on-item-smelted
  [^PlayerEvent$ItemSmeltedEvent event]
  (when-let [player (.getEntity event)]
    (when (instance? Player player)
      (let [item-id (item-stack->id (.getSmelting event))]
        (dispatch-item-event! ^Player player item-id :item-smelted)))))

(defn- on-item-pickup
  [^EntityItemPickupEvent event]
  (when-let [player (.getEntity event)]
    (when (instance? Player player)
      (let [item-entity (.getItem event)
            item-id (when item-entity
                      (item-stack->id (.getItem item-entity)))]
        (dispatch-item-event! ^Player player item-id :item-pickup)))))

;; ============================================================================
;; Tick-based activation batching
;; ============================================================================

(def ^:private tick-counter (atom 0))

(defn- on-player-tick
  "Called on PlayerTickEvent (SERVER, END phase).
  Every 3 ticks, calls process-pending-activations! for the player
  to batch-check newly-satisfied tutorial conditions."
  [^TickEvent$PlayerTickEvent event]
  (when (and (= TickEvent$Phase/END (.phase event))
             (instance? ServerPlayer (.player event)))
    (let [c (swap! tick-counter inc)]
      (when (zero? (mod c 3))
        (let [^ServerPlayer player (.player event)]
          (try
            (tutorial-platform/process-pending-activations! (str (.getUUID player)))
            (catch Throwable _)))))))

(defn- install-forge-tutorial-activated-bridge!
  []
  (tutorial-platform/register-tutorial-activated-hook!
   (fn [player-uuid tut-id]
     (try
       (let [uuid (java.util.UUID/fromString player-uuid)
             mc (Minecraft/getInstance)
             player (if (and mc (.hasSingleplayerServer mc))
                      (some-> mc .getSingleplayerServer .getPlayerList (.getPlayer uuid))
                      (when-let [level (some-> mc .level)]
                        (some (fn [^Player p]
                                (when (= (str (.getUUID p)) (str uuid)) p))
                              (.players level))))]
         (when player
           (.post MinecraftForge/EVENT_BUS
                (cn.li.forge1201.event.TutorialActivatedEvent. player (name tut-id)))))
       (catch Throwable _)))))

;; ============================================================================
;; Registration
;; ============================================================================

(def ^:private listener-registered? (atom false))

(defn init!
  "Register Forge item event listeners for tutorial condition tracking
  and tick-based activation batching.
  Called from Forge client/server setup.  Idempotent."
  []
  (when-not @listener-registered?
    (locking listener-registered?
      (when-not @listener-registered?
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false
                      PlayerEvent$ItemCraftedEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-item-crafted evt))))
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false
                      PlayerEvent$ItemSmeltedEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-item-smelted evt))))
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false
                      EntityItemPickupEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-item-pickup evt))))
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false
                      TickEvent$PlayerTickEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-player-tick evt))))
        (try
          (install-forge-tutorial-activated-bridge!)
          (catch Throwable _))
        (reset! listener-registered? true)
        (log/info "Tutorial item event listeners registered"))))
  nil)
