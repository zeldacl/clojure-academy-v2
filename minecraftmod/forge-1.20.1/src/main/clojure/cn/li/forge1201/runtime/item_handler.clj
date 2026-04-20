(ns cn.li.forge1201.runtime.item-handler
  "Item use event handler for runtime-driven items (Forge layer)."
  (:require [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [clojure.string :as str]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickItem]
           [net.minecraftforge.event.entity.living LivingEntityUseItemEvent$Finish]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.world InteractionResult]
           [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.resources ResourceLocation]))


(defn- get-item-id
  "Get item registry ID from ItemStack."
  [^ItemStack stack]
  (when-not (.isEmpty stack)
    (try
      (let [item (.getItem stack)
            ^ResourceLocation registry-name (.getKey BuiltInRegistries/ITEM item)]
        (when registry-name
          (str (.getNamespace registry-name) ":" (.getPath registry-name))))
      (catch Exception e
        (log/warn "Failed to get item ID:" (ex-message e))
        nil))))

(defn- resolve-dsl-item-spec
  "Resolve DSL item spec from runtime item id (`namespace:path`)."
  [item-id]
  (when-let [[_namespace registry-path] (some-> item-id (str/split #":" 2))]
    (some (fn [dsl-id]
            (when (= registry-path (registry-metadata/get-item-registry-name dsl-id))
              (registry-metadata/get-item-spec dsl-id)))
          (registry-metadata/get-all-item-ids))))

(defn- dispatch-dsl-item-right-click!
  [^PlayerInteractEvent$RightClickItem event player item-id side]
  (when-let [item-spec (resolve-dsl-item-spec item-id)]
    (let [ret (idsl/handle-right-click item-spec {:player player
                                                  :item-id item-id
                                                  :item-spec item-spec
                                                  :item-stack (.getItemStack event)
                                                  :hand (.getHand event)
                                                  :side side})]
      (when (:consume? ret)
        (.setCancellationResult event InteractionResult/CONSUME)
        (.setCanceled event true)))))

(defn- dispatch-dsl-item-use!
  [player item-id hand stack side]
  (when-let [item-spec (resolve-dsl-item-spec item-id)]
    (idsl/handle-use item-spec {:player player
                                :item-id item-id
                                :item-spec item-spec
                                :item-stack stack
                                :hand hand
                                :side side})))

(defn- on-item-finish-using
  "Handle finish using item event (e.g. food/charge complete)."
  [^LivingEntityUseItemEvent$Finish event]
  (try
    (let [entity (.getEntity event)]
      (when (instance? Player entity)
        (let [^Player player entity
              stack (.getItem event)
              item-id (get-item-id stack)
              side (if (.isClientSide (.level player)) :client :server)]
          (when-let [item-spec (resolve-dsl-item-spec item-id)]
            (idsl/handle-finish-using item-spec {:player player
                                                 :item-id item-id
                                                 :item-spec item-spec
                                                 :item-stack stack
                                                 :side side})))))
    (catch Exception e
      (log/error "Error handling item finish-using event" e))))

(defn- on-item-use
  "Handle item right-click event."
  [^PlayerInteractEvent$RightClickItem event]
  (try
    (let [^InteractionHand hand (.getHand event)]
      (when (= hand InteractionHand/MAIN_HAND)
        (let [player (.getEntity event)
              player-uuid (str (.getUUID player))
              ability-activated? (boolean (get-in (power-runtime/get-player-state player-uuid)
                                                  [:resource-data :activated]))
              stack (.getItemStack event)
              item-id (get-item-id stack)
              side (if (.isClientSide (.level player)) :client :server)
              plan (power-runtime/build-item-use-plan player-uuid item-id ability-activated? side)]

              (dispatch-dsl-item-use! player item-id hand stack side)

          (when plan
            (doseq [action (:client-actions plan)]
              (case (:kind action)
                :notify-local-effect
                (power-runtime/client-notify-charge-coin-throw! player-uuid)

                :open-screen
                (client-bridge/open-skill-tree-screen! (.getUUID player))

                nil))

            (doseq [action (:server-actions plan)]
              (case (:kind action)
                :consume-item
                (when (and (not (.isClientSide (.level player)))
                           (or (not (:unless-instabuild? action))
                               (not (.. player (getAbilities) instabuild))))
                  (when (and stack (not (.isEmpty stack)))
                    (.shrink stack (int (or (:count action) 1)))
                    (when (.isEmpty stack)
                      (.setItemInHand player hand ItemStack/EMPTY))))

                :domain-action
                (power-runtime/on-runtime-item-action! (:action action) player-uuid (:payload action))

                nil))

            (when (:consume? plan)
              (.setCancellationResult event InteractionResult/CONSUME)
              (.setCanceled event true)))

          (dispatch-dsl-item-right-click! event player item-id side))))
    (catch Exception e
      (log/error "Error handling item use event" e))))

(defn init!
  "Initialize item use event handler."
  []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL
                false
                PlayerInteractEvent$RightClickItem
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-item-use evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL
                false
                LivingEntityUseItemEvent$Finish
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-item-finish-using evt))))
  (log/info "Runtime item handler initialized"))
