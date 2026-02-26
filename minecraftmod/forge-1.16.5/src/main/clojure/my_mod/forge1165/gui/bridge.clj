(ns my-mod.forge1165.gui.bridge
  "Forge 1.16.5 GUI Bridge - Platform-neutral Container wrapper
  
  This module provides platform-specific Java interop without game logic.
  Game-specific concepts (Wireless, Node, Matrix) are abstracted away.
  
  Classes:
  - ForgeContainerBridge: Generic Container wrapper
  - ForgeContainerProviderBridge: Generic INamedContainerProvider"
  (:require [my-mod.wireless.gui.container-dispatcher :as dispatcher]
            [my-mod.wireless.gui.gui-metadata :as gui-metadata]
            [my-mod.wireless.gui.slot-manager :as slot-manager]
            [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.util.log :as log])
  (:import [net.minecraft.entity.player PlayerEntity PlayerInventory]
           [net.minecraft.inventory.container Container INamedContainerProvider]
           [net.minecraft.util.text ITextComponent StringTextComponent]
           [net.minecraft.network PacketBuffer]))
;; ============================================================================
;; Java Container Wrapper
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.ForgeContainerBridge
  :extends net.minecraft.inventory.container.Container
  :state state
  :init init
  :constructors {[int Object clojure.lang.IPersistentMap] [net.minecraft.inventory.container.ContainerType]}
  :methods [[getClojureContainer [] Object]
            [tick [] void]
            [detectAndSendChanges [] void]
            [addSlot [net.minecraft.inventory.container.Slot] net.minecraft.inventory.container.Slot]])

(defn -init
  "Initialize Java Container wrapper
  
  Args:
  - container-id: int (window ID)
  - menu-type: ContainerType
  - clj-container: Clojure container (NodeContainer or MatrixContainer)"
  [container-id menu-type clj-container]
  [[menu-type] (atom clj-container)])

(defn -getClojureContainer
  "Get the wrapped Clojure container"
  [this]
  @(.state this))

(defn -tick
  "Tick the container (called every frame on server)"
  [this]
  (dispatcher/safe-tick! (-getClojureContainer this)))

(defn -stillValid
  "Check if player can still use this container"
  [this player]
  (dispatcher/safe-validate (-getClojureContainer this) player))

(defn -removed
  "Called when container is closed"
  [this player]
  (let [clj-container (-getClojureContainer this)]
    (gui-registry/unregister-active-container! clj-container)
    (gui-registry/unregister-player-container! player)
    (log/info "Container closed for player" (.getName player))))

(defn -detectAndSendChanges
  "Detect and send changes to clients (called every tick on server)"
  [this]
  ;; Call superclass method to handle listener notifications
  (.detectAndSendChanges (.superclass (class this)) this)
  
  ;; Sync Clojure container data using dispatcher
  (dispatcher/safe-sync! (-getClojureContainer this)))

(defn -addSlot
  "Add a slot to the container"
  [this slot]
  (.addSlot (.superclass (class this)) this slot))

(defn -quickMoveStack
  "Handle Shift+Click item movement
  
  Delegates to slot-manager for platform-agnostic logic.
  
  Returns: ItemStack that couldn't be moved (or EMPTY)"
  [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasItem slot))
        (let [stack (.getItem slot)
              clj-container (-getClojureContainer this)]
          ;; Delegate to slot-manager for quick-move logic
          (slot-manager/execute-quick-move-forge this clj-container slot-index slot stack))
        net.minecraft.item.ItemStack/EMPTY))
    (catch Exception e
      (log/error "Error in quickMoveStack:" (.getMessage e))
      net.minecraft.item.ItemStack/EMPTY)))

(defn -canTakeItemForPickAll
  "Check if item can be taken for pick-all (middle-click)
  
  Returns: boolean"
  [this stack slot]
  true)

(defn -canDragTo
  "Check if player can drag items to this slot
  
  Returns: boolean"
  [this slot]
  true)

;; ============================================================================
;; Container Provider
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.ForgeContainerProviderBridge
  :implements [net.minecraft.inventory.container.INamedContainerProvider]
  :state state
  :init init
  :constructors {[int Object] []}
  :methods [[getGuiId [] int]
            [getTileEntity [] Object]])

(defn -init
  "Initialize Container Provider
  
  Args:
  - gui-id: int (0=Node, 1=Matrix)
  - tile-entity: TileEntity instance"
  [gui-id tile-entity]
  [[] {:gui-id gui-id :tile-entity tile-entity}])

(defn -getGuiId [this]
  (:gui-id (.state this)))

(defn -getTileEntity [this]
  (:tile-entity (.state this)))

(defn -getDisplayName
  "Get display name for GUI"
  [this]
  (StringTextComponent.
    (gui-metadata/get-display-name (-getGuiId this))))

(defn -createMenu
  "Create the server-side container
  
  This is called by Forge when opening the GUI"
  [this window-id player-inventory player]
  (let [gui-id (-getGuiId this)
        tile-entity (-getTileEntity this)
        handler (gui-registry/get-gui-handler)
        world (.getWorld player)
        pos (if tile-entity
              (:pos tile-entity)
              (.getPosition player))]
    
    (log/info "Creating container for GUI" gui-id)
    
    ;; Create Clojure container
    (let [clj-container (.get-server-container handler gui-id player world pos)]
      (when-not clj-container
        (throw (ex-info "Failed to create Clojure container"
                       {:gui-id gui-id :player player})))
      
      ;; Register active container
      (gui-registry/register-active-container! clj-container)
      (gui-registry/register-player-container! player clj-container)
      
      ;; Get MenuType from metadata registry
      (let [menu-type (gui-metadata/get-menu-type :forge-1.16.5 gui-id)]
        
        (when-not menu-type
          (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
        
        ;; Create Java Container wrapper
        (my_mod.forge1165.gui.ForgeContainerBridge. window-id menu-type clj-container))))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-container-provider
  "Create a Container Provider for opening GUI
  
  Args:
  - gui-id: int (0=Node, 1=Matrix)
  - tile-entity: TileEntity instance
  
  Returns: INamedContainerProvider instance"
  [gui-id tile-entity]
  (my_mod.forge1165.gui.ForgeContainerProviderBridge. gui-id tile-entity))

(defn wrap-clojure-container
  "Wrap a Clojure container in Java Container
  
  Args:
  - window-id: int
  - menu-type: ContainerType
  - clj-container: Clojure container
  
  Returns: Container instance"
  [window-id menu-type clj-container]
  (my_mod.forge1165.gui.ForgeContainerBridge. window-id menu-type clj-container))
