(ns my-mod.wireless.virtual-blocks
  "Virtual block reference system for wireless network
  
  Provides position-based TileEntity references that:
  - Support NBT serialization (avoiding direct TileEntity refs)
  - Lazy loading (only fetch when needed)
  - Chunk loading safety
  - Distance calculations"
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.interfaces :as interfaces]))

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
  (let [pos (.getPos tile-entity)]
    (->VBlock
      (.getX pos)
      (.getY pos)
      (.getZ pos)
      block-type
      ignore-chunk)))

(defn create-vmatrix
  "Create virtual matrix reference (always ignore chunk)"
  [matrix-tile]
  (create-vblock matrix-tile :matrix true))

(defn create-vnode
  "Create virtual node reference for WirelessNet (check chunks)"
  [node-tile]
  (create-vblock node-tile :node false))

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
  "Get BlockPos for vblock"
  [vblock]
  (net.minecraft.util.math.BlockPos. (:x vblock) (:y vblock) (:z vblock)))

(defn is-chunk-loaded?
  "Check if the chunk containing this vblock is loaded"
  [vblock world]
  (let [chunk-x (bit-shift-right (:x vblock) 4)
        chunk-z (bit-shift-right (:z vblock) 4)]
    (.isChunkGeneratedAt world chunk-x chunk-z)))

(defn vblock-get
  "Get the TileEntity for this vblock (lazy loading)
  Returns nil if:
  - Chunk not loaded (unless ignore-chunk)
  - World is nil
  - TileEntity doesn't exist
  - TileEntity type doesn't match (protocol implementation)"
  [vblock world]
  (when world
    (when (or (:ignore-chunk vblock) (is-chunk-loaded? vblock world))
      (let [pos (vblock-pos vblock)
            tile (.getTileEntity world pos)]
        (when tile
          ;; Type checking based on block-type - using protocol satisfies? checks
          (case (:block-type vblock)
            :matrix (when (interfaces/wireless-matrix? tile) tile)
            :node (when (interfaces/wireless-node? tile) tile)
            :node-conn (when (interfaces/wireless-node? tile) tile)
            :generator (when (interfaces/wireless-generator? tile) tile)
            :receiver (when (interfaces/wireless-receiver? tile) tile)
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
  "Serialize vblock to NBT"
  [vblock]
  (doto (net.minecraft.nbt.NBTTagCompound.)
    (.setInteger "x" (:x vblock))
    (.setInteger "y" (:y vblock))
    (.setInteger "z" (:z vblock))
    (.setString "type" (name (:block-type vblock)))
    (.setBoolean "ignoreChunk" (:ignore-chunk vblock))))

(defn vblock-from-nbt
  "Deserialize vblock from NBT"
  [nbt]
  (->VBlock
    (.getInteger nbt "x")
    (.getInteger nbt "y")
    (.getInteger nbt "z")
    (keyword (.getString nbt "type"))
    (.getBoolean nbt "ignoreChunk")))

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
  [vblocks world]
  (filterv #(some? (vblock-get % world)) vblocks))

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
