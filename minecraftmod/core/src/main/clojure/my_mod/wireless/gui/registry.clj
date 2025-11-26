(ns my-mod.wireless.gui.registry
  "Wireless GUI registration and opening system"
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.node-gui :as node-gui]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
            [my-mod.wireless.gui.matrix-gui :as matrix-gui]
            [my-mod.util.log :as log]))

;; ============================================================================
;; GUI ID Constants
;; ============================================================================

(def gui-wireless-node 0)
(def gui-wireless-matrix 1)

;; ============================================================================
;; GUI Handler Protocol
;; ============================================================================

(defprotocol IGuiHandler
  "Protocol for GUI opening and container creation"
  (get-server-container [this gui-id player world pos]
    "Create server-side container")
  (get-client-gui [this gui-id player world pos]
    "Create client-side GUI screen"))

;; ============================================================================
;; Wireless GUI Handler Implementation
;; ============================================================================

(defrecord WirelessGuiHandler []
  IGuiHandler
  
  (get-server-container [_ gui-id player world pos]
    (case gui-id
      ;; Wireless Node
      0
      (let [tile-entity (.getTileEntity world pos)]
        (when tile-entity
          (log/info "Creating Node container for player" (.getName player))
          (node-container/create-container tile-entity player)))
      
      ;; Wireless Matrix
      1
      (let [tile-entity (.getTileEntity world pos)]
        (when tile-entity
          (log/info "Creating Matrix container for player" (.getName player))
          (matrix-container/create-container tile-entity player)))
      
      ;; Unknown GUI
      (do
        (log/warn "Unknown GUI ID:" gui-id)
        nil)))
  
  (get-client-gui [_ gui-id player world pos]
    (case gui-id
      ;; Wireless Node
      0
      (let [tile-entity (.getTileEntity world pos)]
        (when tile-entity
          (log/info "Creating Node GUI for player" (.getName player))
          (let [container (node-container/create-container tile-entity player)
                minecraft-container nil] ; Will be set by platform impl
            (node-gui/create-screen container minecraft-container))))
      
      ;; Wireless Matrix
      1
      (let [tile-entity (.getTileEntity world pos)]
        (when tile-entity
          (log/info "Creating Matrix GUI for player" (.getName player))
          (let [container (matrix-container/create-container tile-entity player)
                minecraft-container nil] ; Will be set by platform impl
            (matrix-gui/create-screen container minecraft-container))))
      
      ;; Unknown GUI
      (do
        (log/warn "Unknown GUI ID:" gui-id)
        nil))))

;; ============================================================================
;; Global Handler Instance
;; ============================================================================

(defonce ^:private gui-handler (atom nil))

(defn get-gui-handler
  "Get the global GUI handler instance"
  []
  (or @gui-handler
      (reset! gui-handler (->WirelessGuiHandler))))

;; ============================================================================
;; GUI Opening API
;; ============================================================================

(defn open-gui
  "Open a GUI for a player
  
  Args:
  - player: EntityPlayer instance
  - gui-id: GUI identifier (0=Node, 1=Matrix)
  - world: World instance
  - pos: BlockPos instance
  
  This is a platform-agnostic API. Platform-specific implementations
  should call this and then use NetworkHooks or equivalent."
  [player gui-id world pos]
  (log/info "Opening GUI" gui-id "for player" (.getName player) "at" pos)
  
  ;; Validate GUI ID
  (when-not (contains? #{gui-wireless-node gui-wireless-matrix} gui-id)
    (log/warn "Invalid GUI ID:" gui-id)
    (throw (ex-info "Invalid GUI ID" {:gui-id gui-id})))
  
  ;; Validate tile entity exists
  (let [tile-entity (.getTileEntity world pos)]
    (when-not tile-entity
      (log/warn "No tile entity at position:" pos)
      (throw (ex-info "No tile entity at position" {:pos pos}))))
  
  ;; Return gui-id and handler for platform impl to use
  {:gui-id gui-id
   :handler (get-gui-handler)
   :player player
   :world world
   :pos pos})

(defn open-node-gui
  "Convenience function to open Wireless Node GUI"
  [player world pos]
  (open-gui player gui-wireless-node world pos))

(defn open-matrix-gui
  "Convenience function to open Wireless Matrix GUI"
  [player world pos]
  (open-gui player gui-wireless-matrix world pos))

;; ============================================================================
;; Registration (for platform-specific implementations)
;; ============================================================================

(defmulti register-gui-handler
  "Register GUI handler with platform-specific system
  
  This should be implemented by platform modules (forge/fabric)"
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [platform-type]
  (log/warn "No GUI handler registration for platform:" platform-type)
  nil)

;; Platform implementations should look like:
;;
;; (defmethod register-gui-handler :forge-1.16.5 [_]
;;   (let [handler (get-gui-handler)]
;;     ;; Register with Forge's NetworkRegistry
;;     ...))
;;
;; (defmethod register-gui-handler :fabric-1.20.1 [_]
;;   (let [handler (get-gui-handler)]
;;     ;; Register with Fabric's ScreenHandlerRegistry
;;     ...))

;; ============================================================================
;; Container Tick Management
;; ============================================================================

(defonce ^:private active-containers (atom #{}))

(defn register-active-container!
  "Register a container as active (for tick updates)"
  [container]
  (swap! active-containers conj container)
  (log/info "Registered active container, total:" (count @active-containers)))

(defn unregister-active-container!
  "Unregister a container when closed"
  [container]
  (swap! active-containers disj container)
  (log/info "Unregistered container, remaining:" (count @active-containers)))

(defn tick-all-containers!
  "Tick all active containers (called from server tick event)
  
  This should be called every server tick to update container data"
  []
  (doseq [container @active-containers]
    (try
      (cond
        ;; Node container
        (instance? my_mod.wireless.gui.node_container.NodeContainer container)
        (node-container/tick! container)
        
        ;; Matrix container
        (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container)
        (matrix-container/tick! container)
        
        :else
        (log/warn "Unknown container type:" (type container)))
      (catch Exception e
        (log/error "Error ticking container:" e)))))

;; ============================================================================
;; Network Synchronization
;; ============================================================================

(defn get-container-sync-packet
  "Create a network packet for container data synchronization
  
  Args:
  - container: Container instance
  
  Returns: Map with sync data"
  [container]
  (cond
    ;; Node container
    (instance? my_mod.wireless.gui.node_container.NodeContainer container)
    {:type :node
     :data (node-container/get-sync-data container)}
    
    ;; Matrix container
    (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container)
    {:type :matrix
     :data (matrix-container/get-sync-data container)}
    
    :else
    (do
      (log/warn "Cannot sync unknown container type:" (type container))
      nil)))

(defn apply-container-sync-packet
  "Apply sync packet data to client-side container
  
  Args:
  - container: Client-side container instance
  - packet-data: Data from server sync packet"
  [container packet-data]
  (let [{:keys [type data]} packet-data]
    (case type
      :node
      (do
        ;; Update node container atoms
        (reset! (:energy container) (:energy data))
        (reset! (:max-energy container) (:max-energy data))
        (reset! (:node-type container) (:node-type data))
        (reset! (:is-online container) (:is-online data))
        (reset! (:ssid container) (:ssid data))
        (reset! (:password container) (:password data))
        (reset! (:transfer-rate container) (:transfer-rate data)))
      
      :matrix
      (do
        ;; Update matrix container atoms
        (reset! (:core-level container) (:core-level data))
        (reset! (:plate-count container) (:plate-count data))
        (reset! (:is-working container) (:is-working data))
        (reset! (:capacity container) (:capacity data))
        (reset! (:max-capacity container) (:max-capacity data))
        (reset! (:bandwidth container) (:bandwidth data))
        (reset! (:range container) (:range data)))
      
      (log/warn "Unknown sync packet type:" type))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize GUI system
  
  Args:
  - platform-type: Keyword identifying platform (:forge-1.16.5, etc.)"
  [platform-type]
  (log/info "Initializing Wireless GUI system for platform:" platform-type)
  
  ;; Register GUI handler
  (register-gui-handler platform-type)
  
  ;; Initialize handler
  (get-gui-handler)
  
  (log/info "Wireless GUI system initialized"))
