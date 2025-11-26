(ns my-mod.forge1201.gui.bridge
  "Forge 1.20.1 GUI Bridge - Platform-neutral Menu wrapper
  
  This module provides platform-specific Java interop without game logic.
  Game-specific concepts abstracted away through dispatcher and metadata.
  
  API Changes from 1.16.5:
  - Container → AbstractContainerMenu
  - INamedContainerProvider → MenuProvider
  - Package: net.minecraft.inventory.container → net.minecraft.world.inventory
  
  Classes:
  - ForgeMenuBridge: Generic AbstractContainerMenu wrapper
  - ForgeMenuProviderBridge: Generic MenuProvider"
  (:require [my-mod.wireless.gui.container-dispatcher :as dispatcher]
            [my-mod.wireless.gui.gui-metadata :as gui-metadata]
            [my-mod.wireless.gui.slot-manager :as slot-manager]
            [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.entity.player Player Inventory]
           [net.minecraft.world.inventory AbstractContainerMenu MenuType]
           [net.minecraft.network.chat Component]
           [net.minecraft.network FriendlyByteBuf]))

;; ============================================================================
;; Java AbstractContainerMenu Wrapper (Forge 1.20.1)
;; ============================================================================

(gen-class
  :name my_mod.forge1201.gui.ForgeMenuBridge
  :extends net.minecraft.world.inventory.AbstractContainerMenu
  :state state
  :init init
  :constructors {[int Object clojure.lang.IPersistentMap] [net.minecraft.world.inventory.MenuType]}
  :methods [[getClojureContainer [] Object]
            [tick [] void]
            [broadcastChanges [] void]
            [addSlot [net.minecraft.world.inventory.Slot] net.minecraft.world.inventory.Slot]])

(defn -init
  "Initialize Java Menu wrapper"
  [menu-id menu-type clj-container]
  [[menu-type] (atom clj-container)])

(defn -getClojureContainer [this]
  @(.state this))

(defn -tick [this]
  (dispatcher/safe-tick! (-getClojureContainer this)))

(defn -stillValid [this player]
  (dispatcher/safe-validate (-getClojureContainer this) player))

(defn -removed [this player]
  (let [clj-container (-getClojureContainer this)]
    (gui-registry/unregister-active-container! clj-container)
    (log/info "Menu closed for player" (.getName player))))

(defn -broadcastChanges [this]
  ;; Call superclass
  (.broadcastChanges (.superclass (class this)) this)
  
  ;; Sync Clojure container using dispatcher
  (dispatcher/safe-sync! (-getClojureContainer this)))

(defn -addSlot [this slot]
  (.addSlot (.superclass (class this)) this slot))

(defn -quickMoveStack [this player slot-index]
  "Handle Shift+Click item movement
  
  Delegates to slot-manager for platform-agnostic logic."
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasItem slot))
        (let [stack (.getItem slot)
              clj-container (-getClojureContainer this)]
          ;; Delegate to slot-manager for quick-move logic
          (slot-manager/execute-quick-move-forge this clj-container slot-index slot stack))
        net.minecraft.world.item.ItemStack/EMPTY))
    (catch Exception e
      (log/error "Error in quickMoveStack:" (.getMessage e))
      net.minecraft.world.item.ItemStack/EMPTY)))

(defn -canTakeItemForPickAll [this stack slot] true)
(defn -canDragTo [this slot] true)

;; ============================================================================
;; MenuProvider (Forge 1.20.1)
;; ============================================================================

(gen-class
  :name my_mod.forge1201.gui.ForgeMenuProviderBridge
  :implements [net.minecraft.world.MenuProvider]
  :state state
  :init init
  :constructors {[int Object] []}
  :methods [[getGuiId [] int]
            [getTileEntity [] Object]])

(defn -init [gui-id tile-entity]
  [[] {:gui-id gui-id :tile-entity tile-entity}])

(defn -getGuiId [this]
  (:gui-id (.state this)))

(defn -getTileEntity [this]
  (:tile-entity (.state this)))

(defn -getDisplayName [this]
  "Get display name from metadata"
  (Component/literal
    (gui-metadata/get-display-name (-getGuiId this))))

(defn -createMenu [this window-id player-inventory player]
  "Create server-side menu using metadata-driven approach"
  (let [gui-id (-getGuiId this)
        tile-entity (-getTileEntity this)
        handler (gui-registry/get-gui-handler)
        world (.level player)
        pos (if tile-entity (:pos tile-entity) (.blockPosition player))]
    
    (log/info "Creating menu for GUI" gui-id)
    
    (let [clj-container (.get-server-container handler gui-id player world pos)]
      (when-not clj-container
        (throw (ex-info "Failed to create Clojure container"
                       {:gui-id gui-id :player player})))
      
      (gui-registry/register-active-container! clj-container)
      
      (let [menu-type (gui-metadata/get-menu-type :forge-1.20.1 gui-id)]
        
        (when-not menu-type
          (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
        
        (my_mod.forge1201.gui.ForgeMenuBridge. window-id menu-type clj-container)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-menu-provider
  "Create a Menu Provider for opening GUI
  
  Args:
  - gui-id: int (GUI identifier)
  - tile-entity: TileEntity instance
  
  Returns: MenuProvider instance"
  [gui-id tile-entity]
  (my_mod.forge1201.gui.ForgeMenuProviderBridge. gui-id tile-entity))

(defn wrap-clojure-container
  "Wrap a Clojure container in Java AbstractContainerMenu
  
  Args:
  - window-id: int
  - menu-type: MenuType
  - clj-container: Clojure container
  
  Returns: AbstractContainerMenu instance"
  [window-id menu-type clj-container]
  (my_mod.forge1201.gui.ForgeMenuBridge. window-id menu-type clj-container))
