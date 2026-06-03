(ns cn.li.mcmod.platform.position
  "Platform-agnostic position abstraction layer."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

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

(def ^:private ^:dynamic *position-factory* nil)

(defn install-position-factory!
  [factory-fn label]
  (prt/install-impl! #'*position-factory* factory-fn (or label "position-factory")))

(defn call-with-position-factory [factory-fn f]
  (binding [*position-factory* factory-fn] (f)))

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
;; Position Navigation (platform-agnostic wrappers)
;; ============================================================================

(def ^:private ^:dynamic *pos-above-fn* nil)

(defn install-pos-above-fn!
  [f label]
  (prt/install-impl! #'*pos-above-fn* f (or label "pos-above")))

(defn pos-above
  "Return the BlockPos directly above the given position.

  Args:
  - pos: IBlockPos

  Returns: IBlockPos one block above"
  [pos]
  (if-let [f *pos-above-fn*]
    (f pos)
    (throw (ex-info "pos-above not initialized - platform must set *pos-above-fn*" {:pos pos}))))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn factory-initialized?
  "Check if the position factory has been initialized by platform code."
  []
  (some? *position-factory*))