(ns my-mod.forge1165.gui.bridge
  "Forge 1.16.5 GUI Bridge - Java Container wrapper for Clojure containers"
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
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
  :name my_mod.forge1165.gui.WirelessContainer
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
  (let [clj-container (-getClojureContainer this)]
    (cond
      ;; Node container
      (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
      (node-container/tick! clj-container)
      
      ;; Matrix container
      (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
      (matrix-container/tick! clj-container)
      
      :else
      (log/warn "Unknown container type in tick:" (type clj-container)))))

(defn -stillValid
  "Check if player can still use this container"
  [this player]
  (let [clj-container (-getClojureContainer this)]
    (cond
      (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
      (node-container/still-valid? clj-container player)
      
      (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
      (matrix-container/still-valid? clj-container player)
      
      :else
      false)))

(defn -removed
  "Called when container is closed"
  [this player]
  (let [clj-container (-getClojureContainer this)]
    (gui-registry/unregister-active-container! clj-container)
    (log/info "Container closed for player" (.getName player))))

(defn -detectAndSendChanges
  "Detect and send changes to clients (called every tick on server)"
  [this]
  ;; Call superclass method to handle listener notifications
  (.detectAndSendChanges (.superclass (class this)) this)
  
  ;; Sync Clojure container data
  (let [clj-container (-getClojureContainer this)]
    (cond
      (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
      (node-container/sync-to-client! clj-container)
      
      (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
      (matrix-container/sync-to-client! clj-container))))

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
  :name my_mod.forge1165.gui.WirelessContainerProvider
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
  (let [gui-id (-getGuiId this)]
    (StringTextComponent.
      (case gui-id
        0 "Wireless Node"
        1 "Wireless Matrix"
        "Wireless GUI"))))

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
      
      ;; Get or create MenuType (should be registered in init)
      (let [menu-type (case gui-id
                        0 my_mod.forge1165.gui.GuiRegistry/NODE_MENU_TYPE
                        1 my_mod.forge1165.gui.GuiRegistry/MATRIX_MENU_TYPE
                        nil)]
        
        (when-not menu-type
          (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
        
        ;; Create Java Container wrapper
        (my_mod.forge1165.gui.WirelessContainer. window-id menu-type clj-container)))))

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
  (my_mod.forge1165.gui.WirelessContainerProvider. gui-id tile-entity))

(defn wrap-clojure-container
  "Wrap a Clojure container in Java Container
  
  Args:
  - window-id: int
  - menu-type: ContainerType
  - clj-container: Clojure container
  
  Returns: Container instance"
  [window-id menu-type clj-container]
  (my_mod.forge1165.gui.WirelessContainer. window-id menu-type clj-container))
