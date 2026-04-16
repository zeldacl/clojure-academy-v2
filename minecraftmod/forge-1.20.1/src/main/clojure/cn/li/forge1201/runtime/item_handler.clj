(ns cn.li.forge1201.runtime.item-handler
  "Item use event handler for ability items (Forge layer)."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickItem]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.world InteractionResult]
           [net.minecraft.world InteractionHand]
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

(defn- on-item-use
  "Handle item right-click event."
  [^PlayerInteractEvent$RightClickItem event]
  (try
    (let [player (.getEntity event)
          player-uuid (str (.getUUID player))
          ability-activated? (boolean (get-in (ability-runtime/get-player-state player-uuid)
                                              [:resource-data :activated]))
          stack (.getItemStack event)
          item-id (get-item-id stack)
          side (if (.isClientSide (.level player)) :client :server)
          plan (ability-runtime/build-item-use-plan player-uuid item-id ability-activated? side)]

      (when plan
        (doseq [action (:client-actions plan)]
          (case (:kind action)
            :notify-local-effect
            (when-let [client-fn (resolve 'cn.li.forge1201.client.runtime-bridge/notify-railgun-coin-throw-client!)]
              (@client-fn player-uuid))

            :open-screen
            (when-let [open-fn (resolve 'cn.li.forge1201.client.screen-host/open-skill-tree-screen!)]
              (@open-fn (.getUUID player)))

            nil))

        (doseq [action (:server-actions plan)]
          (case (:kind action)
            :consume-item
            (when (and (not (.isClientSide (.level player)))
                       (or (not (:unless-instabuild? action))
                           (not (.. player (getAbilities) instabuild))))
              (let [^InteractionHand hand (.getHand event)]
                (when (and stack (not (.isEmpty stack)))
                  (.shrink stack (int (or (:count action) 1)))
                  (when (.isEmpty stack)
                    (.setItemInHand player hand ItemStack/EMPTY)))))

            :domain-action
            (ability-runtime/on-ability-item-action! (:action action) player-uuid (:payload action))

            nil))

        (when (:consume? plan)
          (.setCancellationResult event InteractionResult/CONSUME)
          (.setCanceled event true))))
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
  (log/info "Ability item handler initialized"))
