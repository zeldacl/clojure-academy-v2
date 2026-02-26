(ns my-mod.fabric1201.gui.bridge
  "Fabric 1.20.1 GUI Bridge - Platform-neutral ScreenHandler wrapper
  
  This module provides platform-specific Java interop without game logic.
  
  All GUI framework functionality is accessed through the platform-adapter,
  which abstracts away wireless-specific implementation details.
  
  Classes:
  - FabricScreenHandlerBridge: Generic ScreenHandler wrapper
  - FabricScreenHandlerFactoryBridge: Generic NamedScreenHandlerFactory
  - FabricExtendedScreenHandlerFactoryBridge: Extended factory with packet data"
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.entity.player PlayerEntity PlayerInventory]
           [net.minecraft.screen ScreenHandler ScreenHandlerType]
           [net.minecraft.screen.slot Slot]
           [net.minecraft.text Text]
           [net.minecraft.network PacketByteBuf]
           [net.minecraft.util Identifier]))

;; ============================================================================
;; ScreenHandler Wrapper (Fabric's equivalent to Container/Menu)
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.FabricScreenHandlerBridge
  :extends net.minecraft.screen.ScreenHandler
  :state state
  :init init
  :constructors {[int Object clojure.lang.IPersistentMap] [net.minecraft.screen.ScreenHandlerType int]}
  :methods [[getClojureContainer [] Object]
            [tick [] void]])

(defn -init
  "Initialize ScreenHandler wrapper
  
  Args:
  - sync-id: int (synchronization ID)
  - handler-type: ScreenHandlerType
  - clj-container: Clojure container (NodeContainer or MatrixContainer)"
  [sync-id handler-type clj-container]
  [[handler-type sync-id] (atom clj-container)])

(defn -getClojureContainer
  "Get the wrapped Clojure container"
  [this]
  @(.state this))

(defn -tick
  "Tick the screen handler (called every frame on server)"
  [this]
  (gui/safe-tick! (-getClojureContainer this)))

(defn -canUse
  "Check if player can use this screen handler (Fabric's stillValid)"
  [this player]
  (gui/safe-validate (-getClojureContainer this) player))

(defn -close
  "Called when screen handler is closed"
  [this player]
  (.close (.superclass (class this)) this player)
  (let [clj-container (-getClojureContainer this)]
    ;; Safely close container using dispatcher abstraction
    (gui/safe-close! clj-container)
    
    ;; Unregister from global registries
    (gui/unregister-active-container! clj-container)
    (gui/unregister-player-container! player)
    (log/info "ScreenHandler closed for player" (.getName player))))

(defn -sendContentUpdates
  "Send content updates to clients (Fabric's broadcastChanges)"
  [this]
  ;; Call superclass
  (.sendContentUpdates (.superclass (class this)) this)
  
  ;; Sync Clojure container using dispatcher
  (gui/safe-sync! (-getClojureContainer this)))

(defn -addSlot
  "Add a slot to the screen handler"
  [this slot]
  (.addSlot (.superclass (class this)) this slot))

(defn -quickMove
  "Handle Shift+Click item movement (Fabric's transferSlot)
  
  Delegates to slot-manager for platform-agnostic logic.
  
  Returns: ItemStack that couldn't be moved (or EMPTY)"
  [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasStack slot))
        (let [stack (.getStack slot)
              clj-container (-getClojureContainer this)]
          ;; Delegate to slot-manager for quick-move logic
          (gui/execute-quick-move-fabric this clj-container slot-index slot stack))
        net.minecraft.item.ItemStack/EMPTY))
    (catch Exception e
      (log/error "Error in quickMove:" (.getMessage e))
      net.minecraft.item.ItemStack/EMPTY)))

(defn -canInsertIntoSlot
  "Check if item can be inserted into slot"
  [this stack slot]
  true)

;; ============================================================================
;; NamedScreenHandlerFactory (Fabric's MenuProvider)
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.FabricScreenHandlerFactoryBridge
  :implements [net.minecraft.screen.NamedScreenHandlerFactory]
  :state state
  :init init
  :constructors {[int Object] []}
  :methods [[getGuiId [] int]
            [getTileEntity [] Object]])

(defn -init
  "Initialize ScreenHandler Factory
  
  Args:
  - gui-id: int (0=Node, 1=Matrix)
  - tile-entity: TileEntity instance"
  [gui-id tile-entity]
  [[] {:gui-id gui-id :tile-entity tile-entity}])

(defn -getGuiId [this]
  (:gui-id (.state this)))

(defn -getTileEntity [this]
  (:tile-entity (.state this)))

(defn -getDisplayName [this]
  (Text/literal (gui/get-display-name (-getGuiId this))))

(defn create-node-handler
  "Create a Fabric ScreenHandler for Node containers
  
  Implementation notes:
  - Uses dispatcher for polymorphic container operations (tick, validate, sync)
  - Uses gui-metadata for display names and MenuType lookups
  - Uses slot-manager for inventory layout and quick-move
  - Eliminates hardcoded instance? checks and case statements
  
  Container lifecycle:
  1. tick() - dispatcher/safe-tick! handles container updates
  2. canUse() - dispatcher/safe-validate checks player distance
  3. sendContentUpdates() - dispatcher/safe-sync! syncs to client
  
  Args:
    handler-type: ScreenHandlerType
    sync-id: int - synchronization ID
    player-inventory: PlayerInventory
    pos: BlockPos (packed as long for Fabric)
  
  Returns:
    ScreenHandler instance wrapping NodeContainer"

;; ============================================================================
;; ExtendedScreenHandlerFactory (for additional data)
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.FabricExtendedScreenHandlerFactoryBridge
  :implements [net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory]
  :state state
  :init init-extended
  :constructors {[int Object] []}
  :methods [[getGuiId [] int]
            [getTileEntity [] Object]])

(defn -init-extended [gui-id tile-entity]
  [[] {:gui-id gui-id :tile-entity tile-entity}])

(defn -getGuiId [this]
  (:gui-id (.state this)))

(defn -getTileEntity [this]
  (:tile-entity (.state this)))

(defn -getDisplayName [this]
  (Text/literal (gui/get-display-name (-getGuiId this))))

(defn -createMenu [this sync-id player-inventory player]
  (let [gui-id (-getGuiId this)
        tile-entity (-getTileEntity this)
        handler (gui/get-gui-handler)
        world (.getWorld player)
        pos (if tile-entity (:pos tile-entity) (.getBlockPos player))]
    
    (let [clj-container (.get-server-container handler gui-id player world pos)]
      (when-not clj-container
        (throw (ex-info "Failed to create Clojure container" {:gui-id gui-id})))
      
      (gui/register-active-container! clj-container)
      (gui/register-player-container! player clj-container)
      
      (let [handler-type (gui/get-menu-type :fabric-1.20.1 gui-id)]
        (when-not handler-type
          (throw (ex-info "ScreenHandlerType not registered" {:gui-id gui-id})))
        
        (my_mod.fabric1201.gui.FabricScreenHandlerBridge. sync-id handler-type clj-container))))))

(defn -writeScreenOpeningData
  "Write additional data to packet buffer (sent to client)"
  [this player buf]
  (let [gui-id (-getGuiId this)
        tile-entity (-getTileEntity this)]
    ;; Write GUI ID
    (.writeInt buf gui-id)
    
    ;; Write tile entity position (if exists)
    (if tile-entity
      (let [pos (:pos tile-entity)]
        (.writeBoolean buf true)
        (.writeBlockPos buf pos))
      (.writeBoolean buf false))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-screen-handler-factory
  "Create a simple ScreenHandler Factory
  
  Args:
  - gui-id: int
  - tile-entity: TileEntity
  
  Returns: NamedScreenHandlerFactory"
  [gui-id tile-entity]
  (my_mod.fabric1201.gui.FabricScreenHandlerFactoryBridge. gui-id tile-entity))

(defn create-extended-factory
  "Create an extended factory with packet data
  
  Args:
  - gui-id: int
  - tile-entity: TileEntity
  
  Returns: ExtendedScreenHandlerFactory"
  [gui-id tile-entity]
  (my_mod.fabric1201.gui.FabricExtendedScreenHandlerFactoryBridge. gui-id tile-entity))

(defn wrap-clojure-container
  "Wrap Clojure container in Fabric ScreenHandler
  
  Args:
  - sync-id: int
  - handler-type: ScreenHandlerType
  - clj-container: Clojure container
  
  Returns: ScreenHandler"
  [sync-id handler-type clj-container]
  (my_mod.fabric1201.gui.FabricScreenHandlerBridge. sync-id handler-type clj-container))
