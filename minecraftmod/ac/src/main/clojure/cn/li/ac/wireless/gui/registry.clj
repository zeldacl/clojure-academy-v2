(ns cn.li.ac.wireless.gui.registry
  "Wireless GUI registration and opening system"
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.gui.metadata :as gui-meta]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.platform.world :as pworld]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; GUI Handler Protocol + record implementation moved to mcmod
;; ============================================================================

;; ============================================================================
;; GUI Handler Implementation
;; ============================================================================

;; ============================================================================
;; Helper Functions
;; ============================================================================

(def get-gui-config
  "Get GUI spec/config by gui-id (int).
   Moved to mcmod/gui/handler."
  gui-handler/get-gui-config)

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

;; GUI handler protocol/record implementation moved to mcmod/gui/handler.

;; ============================================================================
;; Global Handler Instance
;; ============================================================================

(def get-gui-handler
  "Get the global GUI handler instance.
   Moved to mcmod/gui/handler."
  gui-handler/get-gui-handler)

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
  (log/info "Opening GUI" gui-id "for player" (entity/player-get-name player) "at" pos)
  
  ;; Validate GUI ID
  (when-not (get-gui-config gui-id)
    (log/warn "Invalid GUI ID:" gui-id)
    (throw (ex-info "Invalid GUI ID" {:gui-id gui-id})))
  
  ;; Validate tile entity exists
  (let [tile-entity (pworld/world-get-tile-entity* world pos)]
    (when-not tile-entity
      (log/warn "No tile entity at position:" pos)
      (throw (ex-info "No tile entity at position" {:pos pos}))))
  
  ;; Return gui-id and handler for platform impl to use
  {:gui-id gui-id
   :handler (get-gui-handler)
   :player player
   :world world
   :pos pos})

(defn open-gui-by-type
  "Open GUI by container type keyword.

  Args:
  - player: EntityPlayer instance
  - container-type: Keyword (:node, :matrix, :solar, etc.)
  - world: World instance
  - pos: BlockPos instance"
  [player container-type world pos]
  (if-let [gui-id (gui-meta/get-gui-id-for-type container-type)]
    (open-gui player gui-id world pos)
    (throw (ex-info "No GUI registered for container type"
                   {:container-type container-type}))))

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

(def active-containers gui-handler/active-containers)
(def player-containers gui-handler/player-containers)
(def menu-containers gui-handler/menu-containers)
(def containers-by-id gui-handler/containers-by-id)
(def client-container gui-handler/client-container)

(defn register-active-container!
  "Register a container as active (for tick updates)"
  [container]
  (swap! active-containers conj container)
  (log/info "Registered active container, total:" (count @active-containers)))

(defn- player-key
  "Stable key for player (UUID) so lookup works regardless of object reference. Uses reflection."
  [player]
  (try
    (entity/player-get-uuid player)
    (catch Exception _ nil)))

(defn register-player-container!
  "Register a container for a specific player (keyed by player UUID for stable lookup)."
  [player container]
  (when-let [k (player-key player)]
    (swap! player-containers assoc k container)
    (log/debug "Registered player container for" player)))

(defn unregister-active-container!
  "Unregister a container when closed"
  [container]
  (swap! active-containers disj container)
  (log/info "Unregistered container, remaining:" (count @active-containers)))

(defn unregister-player-container!
  "Unregister a container for a specific player"
  [player]
  (when-let [k (player-key player)]
    (swap! player-containers dissoc k)
    (log/debug "Unregistered player container for" player)))

(defn get-player-container
  "Get the active container for a player (lookup by UUID so set-tab handler finds it)."
  [player]
  (when-let [k (player-key player)]
    (get @player-containers k)))

(defn get-player-container-from-active
  "Find an open tabbed container for this player by scanning active-containers (same JVM, :player UUID match).
   Use for set-tab when other lookups fail (e.g. integrated server / classloader quirks)."
  [player]
  (when-let [pk (player-key player)]
    (first (filter (fn [c]
                     (and (contains? c :tab-index)
                          (when-let [p (:player c)]
                            (= (player-key p) pk))))
                   @active-containers))))

(defn register-menu-container!
  "Register container for a menu (AbstractContainerMenu). Used so set-tab can find container from player.containerMenu."
  [menu container]
  (swap! menu-containers assoc menu container)
  (log/debug "Registered menu container"))

(defn unregister-menu-container!
  "Unregister container when menu is removed."
  [menu]
  (swap! menu-containers dissoc menu)
  (log/debug "Unregistered menu container"))

(defn get-container-for-menu
  "Get Clojure container for a menu instance. Used by set-tab handler when player.containerMenu is our menu."
  [menu]
  (get @menu-containers menu))

(defn register-container-by-id!
  "Register container by menu containerId (window-id). Most reliable for set-tab lookup across threads/sides."
  [container-id container]
  (swap! containers-by-id assoc (int container-id) container)
  (log/debug "Registered container by id" container-id))

(defn unregister-container-by-id!
  [container-id]
  (swap! containers-by-id dissoc (int container-id))
  (log/debug "Unregistered container by id" container-id))

(defn get-container-by-id
  [container-id]
  (get @containers-by-id (int container-id)))

(defn get-menu-container-id
  "Get AbstractContainerMenu containerId (window-id) via reflection. For set-tab and unregister."
  [menu]
  (when menu
    (or (try
          (entity/menu-get-container-id menu)
          (catch Exception _ nil))
        (try
          (let [f (.getDeclaredField (class menu) "containerId")]
            (.setAccessible f true)
            (.get f menu))
          (catch Exception _ nil)))))

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
