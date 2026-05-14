(ns cn.li.mc1201.runtime.spi-contracts
  "Platform-agnostic SPI contracts with opaque handles.
   
   Adds opaque handle abstractions to core SPI to reduce platform-specific type leakage
   while maintaining backward compatibility with existing SPI implementations.
   
   Opaque Handles:
   - IServerHandle: Platform-agnostic server operations
   - IWorldHandle: Platform-agnostic world operations
   - IEntityHandle: Platform-agnostic entity operations
   
   These handles allow business logic to operate without knowing platform-specific
   types (net.minecraft.server.MinecraftServer, etc.)"
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Opaque Handle Protocols
;; ============================================================================

(defprotocol IServerHandle
  "Platform-agnostic server handle for queries and operations"
  
  (get-server-tick [this]
    "Get current server tick count")
  
  (get-worlds [this]
    "Get all loaded world handles")
  
  (get-world-by-dim [this dimension-keyword]
    "Get world handle by dimension (:overworld, :nether, :end, or dimension ID)")
  
  (get-players [this]
    "Get all connected player handles")
  
  (broadcast-message [this message]
    "Send message to all players on server")
  
  (execute-command [this command-string]
    "Execute a command on the server")
  
  (is-running? [this]
    "Check if server is still running"))

(defprotocol IWorldHandle
  "Platform-agnostic world handle for world queries and operations"
  
  (get-world-name [this]
    "Get world identifier name")
  
  (get-dimension [this]
    "Get dimension keyword (:overworld, :nether, :end, or dimension ID)")
  
  (get-world-tick [this]
    "Get world-specific tick count")
  
  (get-block-state [this x y z]
    "Get block state at position as keyword")
  
  (get-biome [this x z]
    "Get biome at column position")
  
  (get-entities [this]
    "Get all entity handles in this world")
  
  (get-entities-in-box [this x1 y1 z1 x2 y2 z2]
    "Get entity handles in bounding box")
  
  (is-block-loaded? [this x z]
    "Check if chunk at x,z is loaded")
  
  (load-chunk [this x z]
    "Force-load chunk at x,z"))

(defprotocol IEntityHandle
  "Platform-agnostic entity handle"
  
  (get-entity-id [this]
    "Get unique entity ID in world")
  
  (get-entity-uuid [this]
    "Get entity UUID")
  
  (is-player-handle? [this]
    "Check if this handle represents a player")
  
  (is-living? [this]
    "Check if this is a living entity handle")
  
  (get-world-handle [this]
    "Get the world this entity is in")
  
  (teleport-to [this world-handle x y z yaw pitch]
    "Teleport entity to position in world"))

;; ============================================================================
;; SPI Contract Upgrades
;; ============================================================================

(defonce ^:private server-context-spi-impl* (atom nil))
(defonce ^:private network-transport-spi-impl* (atom nil))
(defonce ^:private gui-spi-impl* (atom nil))

;; ============================================================================
;; Enhanced Server Context SPI
;; ============================================================================

(defn register-server-context-spi!
  "Register server-context SPI implementation.
   
   Expects map with:
   - :get-current-server (old API) - returns MinecraftServer
   - :get-server-handle (new API) - returns IServerHandle
   - :install! (optional) - initialization function"
  [{:keys [get-current-server get-server-handle install!] :as impl}]
  (when-not (or get-current-server get-server-handle)
    (throw (ex-info "server-context SPI requires :get-current-server or :get-server-handle"
                    {:impl impl})))
  (reset! server-context-spi-impl* impl)
  (log/info "Server context SPI registered"))

(defn get-server-handle
  "Get opaque server handle (new API)
   Returns IServerHandle implementation"
  []
  (if-let [f (:get-server-handle @server-context-spi-impl*)]
    (f)
    (throw (ex-info "Server handle not available; ensure SPI registered with :get-server-handle"
                    {:hint "register-server-context-spi! requires :get-server-handle"}))))

(defn get-current-server
  "Get platform-specific MinecraftServer (old API, for backward compatibility)"
  []
  (if-let [f (:get-current-server @server-context-spi-impl*)]
    (f)
    nil))

(defn install-server-context-spi!
  "Install server context SPI (called during runtime init)"
  []
  (when-let [install! (:install! @server-context-spi-impl*)]
    (install!))
  nil)

;; ============================================================================
;; Enhanced Network Transport SPI
;; ============================================================================

(defn register-network-transport-spi!
  "Register network transport SPI implementation.
   
   Expects map with:
   - :send-to-player (old API) - sends to net.minecraft.server.level.ServerPlayer
   - :send-to-handle (new API) - sends to IEntityHandle (player)
   - :send-to-all - sends to all players
   - :register-packet - registers packet handler"
  [{:keys [send-to-player send-to-handle send-to-all register-packet] :as impl}]
  (when-not (or send-to-player send-to-handle send-to-all register-packet)
    (throw (ex-info "network-transport SPI requires packet methods"
                    {:impl impl})))
  (reset! network-transport-spi-impl* impl)
  (log/info "Network transport SPI registered"))

(defn send-to-player-handle
  "Send packet to player via handle (new API)"
  [player-handle packet-data]
  (if-let [f (:send-to-handle @network-transport-spi-impl*)]
    (f player-handle packet-data)
    (throw (ex-info "send-to-handle not available in network SPI"))))

(defn send-to-all
  "Send packet to all players"
  [packet-data]
  (if-let [f (:send-to-all @network-transport-spi-impl*)]
    (f packet-data)
    (throw (ex-info "send-to-all not available in network SPI"))))

;; ============================================================================
;; Enhanced GUI SPI
;; ============================================================================

(defn register-gui-spi!
  "Register GUI SPI implementation.
   
   Expects map with:
   - :open-gui (old API) - takes net.minecraft.server.level.ServerPlayer
   - :open-gui-for-handle (new API) - takes IEntityHandle
   - :register-screen-type - registers screen type"
  [{:keys [open-gui open-gui-for-handle register-screen-type] :as impl}]
  (when-not (or open-gui open-gui-for-handle)
    (throw (ex-info "GUI SPI requires GUI opening methods"
                    {:impl impl})))
  (reset! gui-spi-impl* impl)
  (log/info "GUI SPI registered"))

(defn open-gui-for-handle
  "Open GUI for player via handle (new API)"
  [player-handle screen-data]
  (if-let [f (:open-gui-for-handle @gui-spi-impl*)]
    (f player-handle screen-data)
    (throw (ex-info "open-gui-for-handle not available in GUI SPI"))))

;; ============================================================================
;; SPI Status & Diagnostics
;; ============================================================================

(defn spi-status
  "Get current SPI registration status"
  []
  {:server-context (if @server-context-spi-impl* :registered :unregistered)
   :network-transport (if @network-transport-spi-impl* :registered :unregistered)
   :gui (if @gui-spi-impl* :registered :unregistered)})

(defn verify-all-spi!
  "Verify that required SPIs are registered.
   Throws if any required SPI is missing."
  []
  (let [status (spi-status)]
    (when-not (every? #(= :registered (val %)) status)
      (throw (ex-info "Some required SPIs not registered"
                      {:status status}))))
  true)
