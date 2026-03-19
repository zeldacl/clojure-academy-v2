(ns my-mod.wireless.virtual-blocks
  "Virtual block reference system for wireless network.

  Provides position-based TileEntity references that:
  - Support NBT serialization (avoiding direct TileEntity refs)
  - Lazy loading (only fetch when needed)
  - Chunk loading safety
  - Distance calculations

  Type checking (Design-3):
  - For Clojure state maps: use :node-type / :placer-name keys
  - For Java ScriptedBlockEntity: use getCapability().isPresent() via CapabilitySlots"
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.interfaces :as interfaces]
            [my-mod.platform.nbt :as nbt]
            [my-mod.platform.position :as pos]
            [my-mod.platform.capability :as platform-cap]
            [my-mod.platform.position :as pos]
            [my-mod.platform.world :as world]))

;; ============================================================================
;; VBlock Record
;; ============================================================================

(defrecord VBlock
  [x              ; int - x coordinate
   y              ; int - y coordinate
   z              ; int - z coordinate
   block-type     ; keyword - :matrix/:node/:node-conn/:generator/:receiver
   ignore-chunk]) ; boolean - if true, force load chunk

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-vblock
  "Create a virtual block reference from a TileEntity"
  [tile-entity block-type ignore-chunk]
  ;; MC 1.17+ renamed getPos() to getBlockPos() on BlockEntity; try both.
  (let [block-pos (or (try (pos/position-get-block-pos tile-entity) (catch Exception _ nil))
                      (try (pos/position-get-pos tile-entity) (catch Exception _ nil)))]
    (->VBlock
      (pos/position-get-x block-pos)
      (pos/position-get-y block-pos)
      (pos/position-get-z block-pos)
      block-type
      ignore-chunk)))

(defn create-vmatrix
  "Create virtual matrix reference (always ignore chunk)"
  ([matrix-tile]
   (create-vblock matrix-tile :matrix true))
  ([x y z]
   (->VBlock x y z :matrix true)))

(defn create-vnode
  "Create virtual node reference for WirelessNet (check chunks)"
  ([node-tile]
   (create-vblock node-tile :node false))
  ([x y z]
   (->VBlock x y z :node false)))

(defn create-vnode-conn
  "Create virtual node reference for NodeConn (ignore chunks)"
  [node-tile]
  (create-vblock node-tile :node-conn true))

(defn create-vgenerator
  "Create virtual generator reference (ignore chunks)"
  [gen-tile]
  (create-vblock gen-tile :generator true))

(defn create-vreceiver
  "Create virtual receiver reference (ignore chunks)"
  [rec-tile]
  (create-vblock rec-tile :receiver true))

;; ============================================================================
;; Core Operations
;; ============================================================================

(defn vblock-pos
  "Get BlockPos for vblock using platform abstraction"
  [vblock]
  (pos/create-block-pos (:x vblock) (:y vblock) (:z vblock)))

(defn is-chunk-loaded?
  "Check if the chunk containing this vblock is loaded"
  [vblock w]
  (let [chunk-x (bit-shift-right (:x vblock) 4)
        chunk-z (bit-shift-right (:z vblock) 4)]
    (world/world-is-chunk-loaded? w chunk-x chunk-z)))

;; ============================================================================
;; Capability-based type checking
;; ============================================================================

(defn- has-capability?
  "Check if a BlockEntity has a specific capability key registered.
  Works with Forge (CapabilitySlots) and falls back to Clojure protocol check."
  [tile cap-key]
  (try
    (let [cap-slots (requiring-resolve 'my-mod.platform.be/get-capability-slot)]
      (when cap-slots
        (let [cap (cap-slots cap-key)]
          (when cap
            (let [lo (platform-cap/get-capability tile cap nil)]
              (platform-cap/is-present? lo))))))
    (catch Exception _
      ;; Fallback: check if tile is a state map with the right keys
      nil)))

(defn- tile-has-wireless-matrix? [tile]
  (if (map? tile)
    (contains? tile :plate-count)
    (or (has-capability? tile "wireless-matrix")
        (interfaces/wireless-matrix? tile))))

(defn- tile-has-wireless-node? [tile]
  (if (map? tile)
    (contains? tile :node-type)
    (or (has-capability? tile "wireless-node")
        (interfaces/wireless-node? tile))))

(defn- tile-has-wireless-generator? [tile]
  (if (map? tile)
    (interfaces/wireless-generator? tile)
    (or (has-capability? tile "wireless-generator")
        (interfaces/wireless-generator? tile))))

(defn- tile-has-wireless-receiver? [tile]
  (if (map? tile)
    (interfaces/wireless-receiver? tile)
    (or (has-capability? tile "wireless-receiver")
        (interfaces/wireless-receiver? tile))))

(defn vblock-get
  "Get the TileEntity/state for this vblock (lazy loading).

  Returns nil if:
  - Chunk not loaded (unless ignore-chunk)
  - World is nil
  - TileEntity doesn't exist
  - TileEntity type doesn't match expected block-type

  Returns the BlockEntity instance (which holds customState internally)."
  [vblock w]
  (when w
    (when (or (:ignore-chunk vblock) (is-chunk-loaded? vblock w))
      (let [block-pos (vblock-pos vblock)
            tile      (world/world-get-tile-entity w block-pos)]
        (when tile
          (case (:block-type vblock)
            :matrix    (when (tile-has-wireless-matrix?    tile) tile)
            :node      (when (tile-has-wireless-node?      tile) tile)
            :node-conn (when (tile-has-wireless-node?      tile) tile)
            :generator (when (tile-has-wireless-generator? tile) tile)
            :receiver  (when (tile-has-wireless-receiver?  tile) tile)
            nil))))))

(defn dist-sq
  "Calculate squared distance between two vblocks"
  [vblock1 vblock2]
  (let [dx (- (:x vblock1) (:x vblock2))
        dy (- (:y vblock1) (:y vblock2))
        dz (- (:z vblock1) (:z vblock2))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn dist-sq-pos
  "Calculate squared distance from vblock to coordinates"
  [vblock x y z]
  (let [dx (- (:x vblock) x)
        dy (- (:y vblock) y)
        dz (- (:z vblock) z)]
    (+ (* dx dx) (* dy dy) (* dz dz))))

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(defn vblock-to-nbt
  "Serialize vblock to NBT using platform abstraction"
  [vblock]
  (doto (nbt/create-nbt-compound)
    (nbt/nbt-set-int! "x" (:x vblock))
    (nbt/nbt-set-int! "y" (:y vblock))
    (nbt/nbt-set-int! "z" (:z vblock))
    (nbt/nbt-set-string! "type" (name (:block-type vblock)))
    (nbt/nbt-set-boolean! "ignoreChunk" (:ignore-chunk vblock))))

(defn vblock-from-nbt
  "Deserialize vblock from NBT using platform abstraction"
  [compound]
  (->VBlock
    (nbt/nbt-get-int compound "x")
    (nbt/nbt-get-int compound "y")
    (nbt/nbt-get-int compound "z")
    (keyword (nbt/nbt-get-string compound "type"))
    (nbt/nbt-get-boolean compound "ignoreChunk")))

;; ============================================================================
;; Equality and Hashing
;; ============================================================================

(defn vblock-equals?
  "Check if two vblocks refer to the same position"
  [vb1 vb2]
  (and (= (:x vb1) (:x vb2))
       (= (:y vb1) (:y vb2))
       (= (:z vb1) (:z vb2))))

(defn vblock-hash
  "Calculate hash code for vblock (for use in maps)"
  [vblock]
  (bit-xor (:x vblock) (:y vblock) (:z vblock)))

;; Override hashCode and equals for record
;; Note: In Clojure, records already implement hashCode/equals based on fields
;; but we can provide explicit functions for compatibility

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn vblock-to-string
  "Convert vblock to debug string"
  [vblock]
  (format "%s[%d, %d, %d]"
          (name (:block-type vblock))
          (:x vblock)
          (:y vblock)
          (:z vblock)))

(defn validate-vblock
  "Validate that vblock has valid coordinates"
  [vblock]
  (and (integer? (:x vblock))
       (integer? (:y vblock))
       (integer? (:z vblock))
       (keyword? (:block-type vblock))
       (boolean? (:ignore-chunk vblock))))

;; ============================================================================
;; Collection Helpers
;; ============================================================================

(defn remove-invalid-vblocks
  "Remove vblocks that don't resolve to valid TileEntities"
  [vblocks w]
  (filterv #(some? (vblock-get % w)) vblocks))

(defn find-vblock-by-pos
  "Find vblock in collection by coordinates"
  [vblocks x y z]
  (first (filter #(and (= (:x %) x)
                       (= (:y %) y)
                       (= (:z %) z))
                 vblocks)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-virtual-blocks! []
  (log/info "Virtual blocks system initialized"))

;; Export public API
(def ^:export create-vblock create-vblock)
(def ^:export create-vmatrix create-vmatrix)
(def ^:export create-vnode create-vnode)
(def ^:export create-vnode-conn create-vnode-conn)
(def ^:export create-vgenerator create-vgenerator)
(def ^:export create-vreceiver create-vreceiver)
(def ^:export vblock-get vblock-get)
(def ^:export vblock-to-nbt vblock-to-nbt)
(def ^:export vblock-from-nbt vblock-from-nbt)
(def ^:export dist-sq dist-sq)
(def ^:export dist-sq-pos dist-sq-pos)
