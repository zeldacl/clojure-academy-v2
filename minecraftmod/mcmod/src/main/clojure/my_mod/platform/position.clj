(ns my-mod.platform.position
  "Platform-agnostic position abstraction layer.
  
  This namespace provides protocols and factory functions for block positions
  without depending on any specific Minecraft version (BlockPos package
  location changed across versions).
  
  Design Philosophy:
  - VBlocks store raw coordinates (x, y, z) as integers
  - BlockPos objects created only when interfacing with platform APIs
  - Platform code extends IBlockPos protocol to Minecraft's BlockPos
  - Core code uses factory to create positions when needed")

;; ============================================================================
;; Position Protocol
;; ============================================================================

(defprotocol IBlockPos
  "Protocol for block position operations.

  Platform implementations extend this to their BlockPos classes."

  (pos-x [this]
    "Get X coordinate")

  (pos-y [this]
    "Get Y coordinate")

  (pos-z [this]
    "Get Z coordinate"))

;; ============================================================================
;; Position Accessor Protocol (for objects that have positions)
;; ============================================================================

(defprotocol IHasPosition
  "Protocol for objects that have a position (like TileEntity/BlockEntity)."

  (position-get-block-pos [this]
    "Get BlockPos from this object (MC 1.17+ method name)")

  (position-get-pos [this]
    "Get BlockPos from this object (MC 1.16.5 method name)"))

;; Helper functions that work with IBlockPos
(defn position-get-x [pos] (pos-x pos))
(defn position-get-y [pos] (pos-y pos))
(defn position-get-z [pos] (pos-z pos))

;; ============================================================================
;; Platform Factory Registration
;; ============================================================================

(defonce ^{:dynamic true
           :doc "Platform-specific position factory function.
         
         Must be initialized by platform code before core code runs.
         
         Expected signature: (fn [x y z] -> IBlockPos)
         
         Example platform initialization:
         (alter-var-root #'my-mod.platform.position/*position-factory*
           (constantly (fn [x y z] (BlockPos. x y z))))"}
  *position-factory*
  nil)

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-block-pos
  "Create a block position from coordinates.
  
  Args:
  - x: int - X coordinate
  - y: int - Y coordinate  
  - z: int - Z coordinate
  
  Returns: IBlockPos implementation from current platform
  Throws: ex-info if platform not initialized"
  [x y z]
  (if-let [factory *position-factory*]
    (factory x y z)
    (throw (ex-info "Position factory not initialized - platform must call init-platform! first"
                    {:hint "Check that platform mod initialization calls platform-impl/init-platform!"
                     :coords [x y z]}))))

;; ============================================================================
;; Coordinate Map Utilities
;; ============================================================================

(defn coords-to-map
  "Convert coordinate triple to map representation.
  
  Useful for platform-independent position storage."
  [x y z]
  {:x x :y y :z z})

(defn pos-to-map
  "Convert IBlockPos to coordinate map."
  [pos]
  {:x (pos-x pos)
   :y (pos-y pos)
   :z (pos-z pos)})

(defn map-to-pos
  "Convert coordinate map to IBlockPos.
  
  Args:
  - m: map with :x, :y, :z keys
  
  Returns: IBlockPos"
  [{:keys [x y z]}]
  (create-block-pos x y z))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn factory-initialized?
  "Check if the position factory has been initialized by platform code."
  []
  (some? *position-factory*))
