(ns my-mod.forge1165.mod
  "Forge 1.16.5 main mod class - generated with gen-class"
  (:require [my-mod.forge1165.init :as init]
            [my-mod.forge1165.registry :as registry]
            [my-mod.forge1165.events :as events]
            [my-mod.forge1165.gui.impl :as gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.block Block AbstractBlock Material]
           [net.minecraft.block.material Material]
           [net.minecraft.item Item ItemGroup BlockItem]
           [net.minecraftforge.fml.common Mod]
           [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
           [net.minecraftforge.registries DeferredRegister ForgeRegistries]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock])
  (:gen-class
   :name com.example.my_mod1165.MyMod1165Clj
   :prefix "mod-"
   :init init
   :state state
   :constructors {[] []}
   :methods [[onRightClickBlock [net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock] void]
             [setup [net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent] void]]))

;; Mod ID constant
(def mod-id "my_mod")

;; DeferredRegister instances
(defonce blocks-register 
  (DeferredRegister/create ForgeRegistries/BLOCKS mod-id))

(defonce items-register 
  (DeferredRegister/create ForgeRegistries/ITEMS mod-id))

;; Register blocks
(defonce demo-block
  (.register blocks-register "demo_block"
    (reify java.util.function.Supplier
      (get [_]
        (Block. (.. (AbstractBlock$Properties/of Material/STONE)
                    (strength 1.5 6.0)))))))

;; Register items
(defonce demo-item
  (.register items-register "demo_item"
    (reify java.util.function.Supplier
      (get [_]
        (Item. (.tab (Item$Properties.) ItemGroup/TAB_MISC))))))

(defonce demo-block-item
  (.register items-register "demo_block"
    (reify java.util.function.Supplier
      (get [_]
        (BlockItem. (.get demo-block)
                    (.tab (Item$Properties.) ItemGroup/TAB_BUILDING_BLOCKS))))))

;; Constructor implementation
(defn mod-init []
  (log/info "Initializing MyMod1165 from Clojure...")
  
  (let [mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))]
    ;; Register DeferredRegisters
    (.register blocks-register mod-bus)
    (.register items-register mod-bus)
    
    ;; Add setup listener
    (.addListener mod-bus 
      (reify java.util.function.Consumer
        (accept [_ event]
          (log/info "FMLCommonSetupEvent called")))))
  
  ;; Register to gameplay event bus
  (.register (MinecraftForge/EVENT_BUS) 
    (proxy [Object] []
      (onRightClickBlock [^PlayerInteractEvent$RightClickBlock evt]
        (events/handle-right-click-event evt))))
  
  ;; Initialize Clojure adapters
  (init/init-from-java)
  
  ;; Return state
  [[] nil])

;; Setup method implementation
(defn mod-setup [this event]
  (log/info "Common setup phase"))

;; Helper to get registered block/item
(defn get-demo-block []
  (.get demo-block))

(defn get-demo-item []
  (.get demo-item))

(defn get-demo-block-item []
  (.get demo-block-item))
