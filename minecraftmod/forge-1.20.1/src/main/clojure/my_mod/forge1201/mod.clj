(ns my-mod.forge1201.mod
  "Forge 1.20.1 main mod class - generated with gen-class"
  (:require [my-mod.forge1201.init :as init]
            [my-mod.forge1201.registry :as registry]
            [my-mod.forge1201.events :as events]
            [my-mod.forge1201.gui.impl :as gui]
            [my-mod.block.dsl :as bdsl]
            [my-mod.block.demo :as block-demo]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour]
           [net.minecraft.world.item Item BlockItem]
           [net.minecraftforge.fml.common Mod]
           [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
           [net.minecraftforge.registries DeferredRegister ForgeRegistries]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock])
  (:gen-class
   :name com.example.my_mod1201.MyMod1201Clj
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

;; Register blocks using DSL
(defonce demo-block
  (let [block-spec (bdsl/get-block "demo-block")]
    (.register blocks-register "demo_block"
      (reify java.util.function.Supplier
        (get [_]
          (let [props (bdsl/get-block-properties block-spec)]
            (Block. (BlockBehaviour$Properties/copy Blocks/STONE))))))))

;; Register items
(defonce demo-item
  (.register items-register "demo_item"
    (reify java.util.function.Supplier
      (get [_]
        (Item. (Item$Properties.))))))

(defonce demo-block-item
  (.register items-register "demo_block"
    (reify java.util.function.Supplier
      (get [_]
        (BlockItem. (.get demo-block) (Item$Properties.))))))

;; Constructor implementation
(defn mod-init []
  (log/info "Initializing MyMod1201 from Clojure...")
  
  ;; Initialize block DSL
  (block-demo/init-demo-blocks!)
  
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
