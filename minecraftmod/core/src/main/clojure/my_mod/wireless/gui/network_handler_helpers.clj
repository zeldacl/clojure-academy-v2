(ns my-mod.wireless.gui.network-handler-helpers
  "Shared network handler utility functions
  
  Provides common helper functions for server-side network message handlers,
  eliminating duplication between node_network_handler and matrix_network_handler.")

;; ============================================================================
;; World and Tile Access Helpers
;; ============================================================================

(defn get-world
  "Get world instance from player, handling cross-version API differences
  
  Args:
  - player: Player entity instance
  
  Returns: World/Level instance or nil if not accessible"
  [player]
  (or (try (.getWorld player) (catch Exception _ nil))
      (try (.level player) (catch Exception _ nil))
      (try (.getEntityWorld player) (catch Exception _ nil))))

(defn get-tile-at
  "Fetch tile entity at position from network payload
  
  Args:
  - world: World/Level instance
  - payload: Map containing :pos-x, :pos-y, :pos-z keys
  
  Returns: TileEntity/BlockEntity or nil if not found"
  [world {:keys [pos-x pos-y pos-z]}]
  (when (and world (number? pos-x) (number? pos-y) (number? pos-z))
    (let [pos (net.minecraft.util.math.BlockPos. (int pos-x) (int pos-y) (int pos-z))]
      (or (try (.getTileEntity world pos) (catch Exception _ nil))
          (try (.getBlockEntity world pos) (catch Exception _ nil))))))

;; ============================================================================
;; Payload Construction Helpers
;; ============================================================================

(defn tile-pos-payload
  "Extract position payload from a tile entity for network messages
  
  Args:
  - tile: TileEntity instance with :pos field
  
  Returns: Map with :pos-x, :pos-y, :pos-z keys"
  [tile]
  (let [pos (:pos tile)]
    {:pos-x (.getX pos)
     :pos-y (.getY pos)
     :pos-z (.getZ pos)}))
