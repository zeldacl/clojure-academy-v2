(ns my-mod.wireless.gui.registry
  "Wireless GUI registration and opening system"
  (:require [my-mod.gui.dsl :as gui-dsl]
            [my-mod.wireless.gui.gui-metadata :as gui-meta]
            [my-mod.platform.world :as pworld]
            [my-mod.util.log :as log]))

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
;; GUI Handler Implementation
;; ============================================================================

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn get-gui-config
  "Get GUI spec/config by gui-id (int)."
  [gui-id]
  (gui-dsl/get-gui-by-gui-id gui-id))

(defn get-config-by-container
  "Get GUI config by container structure.

  This is used for ticking and syncing all active containers without
  platform-specific knowledge of GUI kinds."
  [container]
  (some (fn [gui-id]
          (let [cfg (get-gui-config gui-id)
                pred (:container-predicate cfg)]
            (when (and pred (pred container))
              cfg)))
        (gui-dsl/list-gui-ids)))

(defmacro defwireless-gui-handler
  "Define a GUI handler driven by gui-config table.
  
  Each function is called only when tile-entity exists." 
  [name]
  `(defrecord ~name []
     IGuiHandler
     (get-server-container [_# gui-id# player# world# pos#]
       (let [tile-entity# (pworld/world-get-tile-entity world# pos#)
             cfg# (get-gui-config gui-id#)]
         (if (and tile-entity# cfg#)
           (do
             (log/info "Creating container for player" (.getName player#) "gui" gui-id#)
             ((:container-fn cfg#) tile-entity# player#))
           (do
             (when-not cfg#
               (log/warn "Unknown GUI ID:" gui-id#))
             nil))))
     (get-client-gui [_# gui-id# player# world# pos#]
       (let [tile-entity# (pworld/world-get-tile-entity world# pos#)
             cfg# (get-gui-config gui-id#)]
         (if (and tile-entity# cfg#)
           (do
             (log/info "Creating GUI for player" (.getName player#) "gui" gui-id#)
             (let [container# ((:container-fn cfg#) tile-entity# player#)]
               ((:screen-fn cfg#) container# nil player#)))
           (do
             (when-not cfg#
               (log/warn "Unknown GUI ID:" gui-id#))
             nil))))))

(defwireless-gui-handler WirelessGuiHandler)

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
  (when-not (get-gui-config gui-id)
    (log/warn "Invalid GUI ID:" gui-id)
    (throw (ex-info "Invalid GUI ID" {:gui-id gui-id})))
  
  ;; Validate tile entity exists
  (let [tile-entity (pworld/world-get-tile-entity world pos)]
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
  "Open Wireless Node GUI (legacy entrypoint used by blocks)."
  [player world pos]
  (open-gui player gui-meta/gui-wireless-node world pos))

(defn open-matrix-gui
  "Open Wireless Matrix GUI (legacy entrypoint used by blocks)."
  [player world pos]
  (open-gui player gui-meta/gui-wireless-matrix world pos))

(defn open-solar-gui
  "Open Solar Generator GUI (legacy entrypoint used by blocks)."
  [player world pos]
  (open-gui player gui-meta/gui-solar-gen world pos))

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
(defonce ^:private player-containers (atom {}))
(defonce ^:private client-container (atom nil))

(defn register-active-container!
  "Register a container as active (for tick updates)"
  [container]
  (swap! active-containers conj container)
  (log/info "Registered active container, total:" (count @active-containers)))

(defn register-player-container!
  "Register a container for a specific player"
  [player container]
  (swap! player-containers assoc player container)
  (log/debug "Registered player container for" player))

(defn unregister-active-container!
  "Unregister a container when closed"
  [container]
  (swap! active-containers disj container)
  (log/info "Unregistered container, remaining:" (count @active-containers)))

(defn unregister-player-container!
  "Unregister a container for a specific player"
  [player]
  (swap! player-containers dissoc player)
  (log/debug "Unregistered player container for" player))

(defn get-player-container
  "Get the active container for a player"
  [player]
  (get @player-containers player))

(defn set-client-container!
  "Set the client-side active container"
  [container]
  (reset! client-container container))

(defn clear-client-container!
  "Clear the client-side active container"
  []
  (reset! client-container nil))

(defn get-client-container
  "Get the client-side active container"
  []
  @client-container)

(defn tick-all-containers!
  "Tick all active containers (called from server tick event)
  
  This should be called every server tick to update container data"
  []
  (doseq [container @active-containers]
    (try
      (if-let [cfg (get-config-by-container container)]
        ((:tick-fn cfg) container)
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
  (if-let [cfg (get-config-by-container container)]
    {:type (:gui-type cfg)
     :data ((:sync-get cfg) container)}
    (do
      (log/warn "Cannot sync unknown container type:" (type container))
      nil)))

(defn apply-container-sync-packet
  "Apply sync packet data to client-side container
  
  Args:
  - container: Client-side container instance
  - packet-data: Data from server sync packet"
  [container packet-data]
  (let [{:keys [type data]} packet-data
        cfg (gui-dsl/get-gui-by-type type)]
    (if cfg
      ((:sync-apply cfg) container data)
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
