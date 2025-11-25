(ns my-mod.fabric1201.mod
  "Fabric 1.20.1 main mod initialization"
  (:require [my-mod.fabric1201.init :as init]
            [my-mod.fabric1201.registry :as registry]
            [my-mod.fabric1201.events :as events]
            [my-mod.fabric1201.gui.impl :as gui]
            [my-mod.util.log :as log]
            [my-mod.defs :as defs])
  (:import [net.minecraft.core Registry]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item Item BlockItem CreativeModeTabs]
           [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour]))

;; Mod ID constant
(def mod-id "my_mod")

;; Create demo block
(defonce demo-block
  (Block. (BlockBehaviour$Properties/copy Blocks/STONE)))

;; Create demo item
(defonce demo-item
  (Item. (.. (Item$Properties.)
             (stacksTo 64))))

;; Create demo block item
(defonce demo-block-item
  (BlockItem. demo-block
              (.. (Item$Properties.)
                  (stacksTo 64))))

(defn register-blocks []
  "Register all blocks"
  (log/info "Registering Fabric blocks...")
  (registry/register-block defs/demo-block-id demo-block))

(defn register-items []
  "Register all items"
  (log/info "Registering Fabric items...")
  (registry/register-item defs/demo-item-id demo-item)
  (registry/register-item defs/demo-block-id demo-block-item))

(defn mod-init []
  "Main mod initialization called from Java ModInitializer"
  (log/info "Initializing MyMod (Fabric 1.20.1) from Clojure...")
  
  ;; Initialize Clojure adapters
  (init/init-from-java)
  
  ;; Register blocks and items
  (register-blocks)
  (register-items)
  
  ;; Register event listeners
  (events/register-events)
  
  (log/info "MyMod (Fabric 1.20.1) initialization complete"))

;; Helper to get registered block/item
(defn get-demo-block []
  demo-block)

(defn get-demo-item []
  demo-item)

(defn get-demo-block-item []
  demo-block-item)
