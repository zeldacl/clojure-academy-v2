(ns cn.li.forge1201.integration.tutorial-events
  "Forge event listeners for tutorial condition-based unlock.

  Listens to ItemCraftedEvent, EntityItemPickupEvent, ItemSmeltedEvent
  and dispatches matching condition checks to ac/tutorial/events via
  requiring-resolve.

  Uses the same MinecraftForge/EVENT_BUS pattern as item_handler.clj."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerEvent$ItemCraftedEvent
                                                 PlayerEvent$ItemSmeltedEvent]
           [net.minecraftforge.event.entity.player EntityItemPickupEvent]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
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
  "Resolve the ac tutorial event handler and call it for an item event."
  [^Player player item-id event-type]
  (when (and player item-id)
    (try
      (let [uuid-str (str (.getUUID player))]
        (when-let [handler (requiring-resolve
                            'cn.li.ac.tutorial.events/on-item-event!)]
          (handler uuid-str item-id event-type)))
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
;; Registration
;; ============================================================================

(def ^:private listener-registered? (atom false))

(defn init!
  "Register Forge item event listeners for tutorial condition tracking.
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
        (reset! listener-registered? true)
        (log/info "Tutorial item event listeners registered"))))
  nil)
