(ns cn.li.ac.wireless.api
  "Wireless system helper functions

  Provides utility functions for querying the wireless system:
  - Network lookups
  - Node connection lookups
  - Range searches
  - Link status checks"
  (:require [cn.li.ac.wireless.data.world :as wd]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network :as network]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Network Queries
;; ============================================================================

(defn- tile-level
  "Get the Level/World from a BlockEntity.
  MC 1.17+ renamed getWorld() to getLevel(); try both for compatibility."
  [tile]
  (platform-be/be-get-world-safe tile))

(defn get-wireless-net-by-matrix
  "Get wireless network by matrix TileEntity"
  [matrix-tile]
  (let [world (tile-level matrix-tile)
        world-data (wd/get-world-data world)
        matrix-vb (vb/create-vmatrix matrix-tile)]
    (wd/get-network-by-matrix world-data matrix-vb)))

(defn get-wireless-net-by-node
  "Get wireless network by node TileEntity"
  [node-tile]
  (let [world (tile-level node-tile)
        world-data (wd/get-world-data world)
        node-vb (vb/create-vnode node-tile)]
    (wd/get-network-by-node world-data node-vb)))

(defn is-node-linked?
  "Check if a node is linked to a wireless network"
  [node-tile]
  (some? (get-wireless-net-by-node node-tile)))

(defn is-matrix-active?
  "Check if a matrix is initialized with an SSID"
  [matrix-tile]
  (some? (get-wireless-net-by-matrix matrix-tile)))

(defn get-nets-in-range
  "Get wireless networks within range of coordinates

  Parameters:
  - world: World
  - x, y, z: coordinates
  - range: search radius
  - max-results: maximum results to return

  Returns: collection of WirelessNet"
  [world x y z range max-results]
  (let [world-data (wd/get-world-data world)]
    (wd/range-search-networks world-data x y z range max-results)))

;; ============================================================================
;; Node Connection Queries
;; ============================================================================

(defn get-node-conn-by-node
  "Get node connection by node TileEntity"
  [node-tile]
  (let [world (platform-be/be-get-world-safe node-tile)
        world-data (wd/get-world-data world)
        node-vb (vb/create-vnode-conn node-tile)]
    (wd/get-node-connection world-data node-vb)))

(defn get-node-conn-by-generator
  "Get node connection by generator TileEntity"
  [gen-tile]
  (let [world (platform-be/be-get-world-safe gen-tile)
        world-data (wd/get-world-data world)
        gen-vb (vb/create-vgenerator gen-tile)]
    (wd/get-node-connection world-data gen-vb)))

(defn get-node-conn-by-receiver
  "Get node connection by receiver TileEntity"
  [rec-tile]
  (let [world (platform-be/be-get-world-safe rec-tile)
        world-data (wd/get-world-data world)
        rec-vb (vb/create-vreceiver rec-tile)]
    (wd/get-node-connection world-data rec-vb)))

(defn is-receiver-linked?
  "Check if a receiver is linked to a node"
  [rec-tile]
  (some? (get-node-conn-by-receiver rec-tile)))

(defn is-generator-linked?
  "Check if a generator is linked to a node"
  [gen-tile]
  (some? (get-node-conn-by-generator gen-tile)))

;; ============================================================================
;; Node Range Search
;; ============================================================================

(defn get-nodes-in-range
  "Get all linkable nodes within range of a position

  Searches for IWirelessNode TileEntities that:
  - Are within their range of the position
  - Have available capacity (load < capacity)

  Uses spatial indexing for efficient chunk-based lookup.

  Parameters:
  - world: World
  - pos: BlockPos

  Returns: list of IWirelessNode TileEntities"
  [world pos]
  (let [search-range 20.0
        max-results 100
        x (pos/pos-x pos)
        y (pos/pos-y pos)
        z (pos/pos-z pos)
        world-data (wd/get-world-data world)
        ;; Get nearby chunks using spatial index
        nearby-chunks (wd/get-nearby-chunks x y z search-range)
        candidate-vblocks (wd/get-vblocks-in-chunks world-data nearby-chunks)
        ;; Filter candidates by actual distance and capacity
        range-sq (* search-range search-range)
        matching-nodes
        (reduce
          (fn [acc node-vb]
            (let [dist-sq (vb/dist-sq-pos node-vb x y z)]
              (if (<= dist-sq range-sq)
                (if-let [node (vb/vblock-get node-vb world)]
                  (let [node-range (.getRange ^cn.li.acapi.wireless.IWirelessNode node)
                        node-range-sq (* node-range node-range)]
                    (if (<= dist-sq node-range-sq)
                      ;; Check capacity via node connection
                      (if-let [conn (wd/get-node-connection world-data node-vb)]
                        (if (< (node-conn/get-load conn)
                               (node-conn/get-capacity conn))
                          (conj acc node)
                          acc)
                        ;; No connection record, node is available
                        (conj acc node))
                      acc))
                  acc)
                acc)))
          []
          candidate-vblocks)]
    (take max-results matching-nodes)))

;; ============================================================================
;; Network Operations (Event-based)
;; ============================================================================

(defn create-network!
  "Create a new wireless network

  Parameters:
  - matrix-tile: IWirelessMatrix TileEntity
  - ssid: network name (String)

  Returns: true if successful"
  [matrix-tile ssid password]
  (let [world (platform-be/be-get-world-safe matrix-tile)
        world-data (wd/get-world-data world)
        matrix-vb (vb/create-vmatrix matrix-tile)]
    (wd/create-network-impl! world-data matrix-vb ssid password)))

(defn destroy-network!
  "Destroy a wireless network"
  [matrix-tile]
  (when-let [network-item (get-wireless-net-by-matrix matrix-tile)]
    (let [world (platform-be/be-get-world-safe matrix-tile)
          world-data (wd/get-world-data world)]
      (wd/destroy-network-impl! world-data network-item))))

(defn link-node-to-network!
  "Link a node to a wireless network

  Parameters:
  - node-tile: IWirelessNode TileEntity
  - matrix-tile: IWirelessMatrix TileEntity
  - password: network password

  Returns: true if successful"
  [node-tile matrix-tile password]
  (when-let [network-item (get-wireless-net-by-matrix matrix-tile)]
    (let [world (platform-be/be-get-world-safe matrix-tile)
          world-data (wd/get-world-data world)
          node-vb (vb/create-vnode node-tile)]
      (wd/link-node-to-network! world-data network-item node-vb password))))

(defn unlink-node-from-network!
  "Unlink a node from its network"
  [node-tile]
  (when-let [network-item (get-wireless-net-by-node node-tile)]
    (let [node-vb (vb/create-vnode node-tile)]
      (network/remove-node! network-item node-vb))))

(defn link-generator-to-node!
  "Link a generator to a node

  Parameters:
  - gen-tile: IWirelessGenerator TileEntity
  - node-tile: IWirelessNode TileEntity
  - password: node password
  - need-auth: whether authentication is required

  Returns: true if successful"
  [gen-tile node-tile password need-auth]
  (when (or (not need-auth)
            (= password (.getPassword ^ cn.li.acapi.wireless.IWirelessNode node-tile)))
    (let [world (platform-be/be-get-world-safe node-tile)
          world-data (wd/get-world-data world)
          node-vb (vb/create-vnode-conn node-tile)
          conn (wd/ensure-node-connection! world-data node-vb)
          gen-vb (vb/create-vgenerator gen-tile)]
      (wd/link-generator-to-node-connection! world-data conn gen-vb))))

(defn unlink-generator-from-node!
  "Unlink a generator from its node"
  [gen-tile]
  (when-let [conn (get-node-conn-by-generator gen-tile)]
    (let [gen-vb (vb/create-vgenerator gen-tile)]
      (node-conn/remove-generator! conn gen-vb))))

(defn link-receiver-to-node!
  "Link a receiver to a node

  Parameters:
  - rec-tile: IWirelessReceiver TileEntity
  - node-tile: IWirelessNode TileEntity
  - password: node password
  - need-auth: whether authentication is required

  Returns: true if successful"
  [rec-tile node-tile password need-auth]
  (when (or (not need-auth)
            (= password (.getPassword ^ cn.li.acapi.wireless.IWirelessNode node-tile)))
    (let [world (platform-be/be-get-world-safe node-tile)
          world-data (wd/get-world-data world)
          node-vb (vb/create-vnode-conn node-tile)
          conn (wd/ensure-node-connection! world-data node-vb)
          rec-vb (vb/create-vreceiver rec-tile)]
      (wd/link-receiver-to-node-connection! world-data conn rec-vb))))

(defn unlink-receiver-from-node!
  "Unlink a receiver from its node"
  [rec-tile]
  (when-let [conn (get-node-conn-by-receiver rec-tile)]
    (let [rec-vb (vb/create-vreceiver rec-tile)]
      (node-conn/remove-receiver! conn rec-vb))))

;; ============================================================================
;; System-wide Operations
;; ============================================================================

(defn tick-wireless-system!
  "Tick all wireless networks and connections in a world"
  [world]
  (when-let [world-data (wd/get-world-data-non-create world)]
    (wd/tick-world-data! world-data)))

(defn save-wireless-data
  "Save wireless data for a world to NBT"
  [world]
  (when-let [world-data (wd/get-world-data-non-create world)]
    (wd/world-data-to-nbt world-data)))

(defn load-wireless-data
  "Load wireless data for a world from NBT"
  [world nbt]
  (wd/world-data-from-nbt world nbt))

;; ============================================================================
;; Debug and Statistics
;; ============================================================================

(defn print-wireless-stats
  "Print wireless system statistics for a world"
  [world]
  (when-let [world-data (wd/get-world-data-non-create world)]
    (wd/print-statistics world-data)))

(defn get-all-networks
  "Get all networks in a world"
  [world]
  (when-let [world-data (wd/get-world-data-non-create world)]
    @(:networks world-data)))

(defn get-all-connections
  "Get all node connections in a world"
  [world]
  (when-let [world-data (wd/get-world-data-non-create world)]
    @(:connections world-data)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-wireless-helper! []
  (log/info "Wireless helper system initialized"))
