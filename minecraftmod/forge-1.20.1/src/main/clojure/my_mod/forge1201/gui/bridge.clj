(ns my-mod.forge1201.gui.bridge
  "Forge 1.20.1 GUI Bridge - Java Container wrapper for Clojure containers
  
  API Changes from 1.16.5:
  - Container → AbstractContainerMenu
  - INamedContainerProvider → MenuProvider
  - Package: net.minecraft.inventory.container → net.minecraft.world.inventory"
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
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
  :name my_mod.forge1201.gui.WirelessMenu
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
  (let [clj-container (-getClojureContainer this)]
    (cond
      (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
      (node-container/tick! clj-container)
      
      (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
      (matrix-container/tick! clj-container)
      
      :else
      (log/warn "Unknown container type in tick:" (type clj-container)))))

(defn -stillValid [this player]
  (let [clj-container (-getClojureContainer this)]
    (cond
      (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
      (node-container/still-valid? clj-container player)
      
      (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
      (matrix-container/still-valid? clj-container player)
      
      :else false)))

(defn -removed [this player]
  (let [clj-container (-getClojureContainer this)]
    (gui-registry/unregister-active-container! clj-container)
    (log/info "Menu closed for player" (.getName player))))

(defn -broadcastChanges [this]
  ;; Call superclass
  (.broadcastChanges (.superclass (class this)) this)
  
  ;; Sync Clojure container
  (let [clj-container (-getClojureContainer this)]
    (cond
      (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
      (node-container/sync-to-client! clj-container)
      
      (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
      (matrix-container/sync-to-client! clj-container))))

(defn -addSlot [this slot]
  (.addSlot (.superclass (class this)) this slot))

(defn -quickMoveStack [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasItem slot))
        (let [stack (.getItem slot)
              clj-container (-getClojureContainer this)]
          (cond
            (instance? my_mod.wireless.gui.node_container.NodeContainer clj-container)
            (if (< slot-index 2)
              (if (.moveItemStackTo this stack 2 38 true)
                (do (.setChanged slot) net.minecraft.world.item.ItemStack/EMPTY)
                stack)
              (if (.moveItemStackTo this stack 0 2 false)
                (do (.setChanged slot) net.minecraft.world.item.ItemStack/EMPTY)
                stack))
            
            (instance? my_mod.wireless.gui.matrix_container.MatrixContainer clj-container)
            (if (< slot-index 4)
              (if (.moveItemStackTo this stack 4 40 true)
                (do (.setChanged slot) net.minecraft.world.item.ItemStack/EMPTY)
                stack)
              (if (.moveItemStackTo this stack 0 4 false)
                (do (.setChanged slot) net.minecraft.world.item.ItemStack/EMPTY)
                stack))
            
            :else stack))
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
  :name my_mod.forge1201.gui.WirelessMenuProvider
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
  (let [gui-id (-getGuiId this)]
    (Component/literal
      (case gui-id
        0 "Wireless Node"
        1 "Wireless Matrix"
        "Wireless GUI"))))

(defn -createMenu [this window-id player-inventory player]
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
      
      (let [menu-type (case gui-id
                        0 my_mod.forge1201.gui.GuiRegistry/NODE_MENU_TYPE
                        1 my_mod.forge1201.gui.GuiRegistry/MATRIX_MENU_TYPE
                        nil)]
        
        (when-not menu-type
          (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
        
        (my_mod.forge1201.gui.WirelessMenu. window-id menu-type clj-container)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-menu-provider [gui-id tile-entity]
  (my_mod.forge1201.gui.WirelessMenuProvider. gui-id tile-entity))

(defn wrap-clojure-container [window-id menu-type clj-container]
  (my_mod.forge1201.gui.WirelessMenu. window-id menu-type clj-container))
