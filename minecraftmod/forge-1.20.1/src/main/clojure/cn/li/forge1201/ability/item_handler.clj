(ns cn.li.forge1201.ability.item-handler
  "Item use event handler for ability items (Forge layer)."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickItem]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.resources ResourceLocation]))


(defn- get-item-id
  "Get item registry ID from ItemStack. Uses reflection to avoid MC bootstrap trigger."
  [^ItemStack stack]
  (when-not (.isEmpty stack)
    (try
      (let [item (.getItem stack)
            ;; Load BuiltInRegistries class and get ITEM field reflectively at runtime
            builtin-regs-cls (Class/forName "net.minecraft.core.registries.BuiltInRegistries")
            item-field (.getField builtin-regs-cls "ITEM")
            item-registry (.get item-field nil)
            ;; Call getKey() method reflectively
            get-key-method (.getMethod (class item-registry) "getKey" (into-array Class [Object]))
            registry-name (.invoke get-key-method item-registry (object-array [item]))]
        (when registry-name
          (str (.invoke (.getMethod (class registry-name) "getNamespace" (into-array Class []))
                        registry-name (object-array []))
               ":"
               (.invoke (.getMethod (class registry-name) "getPath" (into-array Class []))
                        registry-name (object-array [])))))
      (catch Exception e
        (log/warn "Failed to get item ID:" (ex-message e))
        nil))))

(defn- on-item-use
  "Handle item right-click event."
  [^PlayerInteractEvent$RightClickItem event]
  (try
    (let [player (.getEntity event)
          stack (.getItemStack event)
          item-id (get-item-id stack)]

      ;; Check if it's the skill tree app item
      (when (= item-id "ac:app_skill_tree")
        (when (.isClientSide (.level player))
          ;; Open skill tree screen on client side
          (when-let [open-fn (resolve 'cn.li.forge1201.client.ability-screen-bridge/open-skill-tree-screen!)]
            (@open-fn (.getUUID player)))

          ;; Cancel event to prevent other interactions
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
