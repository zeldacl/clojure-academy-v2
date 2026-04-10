(ns cn.li.mcmod.client.render.multiblock-helper
  "Multiblock TESR rendering helper - platform-agnostic
  
  Provides coordinate transformation logic for multiblock structures.
  Replaces functionality of cn.lambdalib2.multiblock.RenderBlockMulti."
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.platform.be :as pbe]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.registry.metadata :as registry-metadata]))

(defn- get-tile-block-id
  [tile]
  (when tile
    (pbe/be-get-block-id tile)))

(defn- normalize-direction [direction]
  (cond
    (keyword? direction) (keyword (str/lower-case (name direction)))
    (string? direction) (keyword (str/lower-case direction))
    (some? direction) (keyword (str/lower-case (str direction)))
    :else :north))

(defn- rotate-rel-pos
  "Legacy BlockMulti rotation for relative coords [x y z]."
  [[x y z] direction]
  (case direction
    :east [(- z) y x]
    :west [z y (- x)]
    :south [(- x) y (- z)]
    [x y z]))

(defn- spec-relative-positions
  "Return relative positions [x y z] including origin for a block spec."
  [block-spec]
  (let [custom (:multi-block-positions block-spec)
        origin [0 0 0]]
    (if (seq custom)
      (let [rel (mapv (fn [m]
                        [(long (or (:x m) (:relative-x m) 0))
                         (long (or (:y m) (:relative-y m) 0))
                         (long (or (:z m) (:relative-z m) 0))])
                      custom)]
        (if (some #(= % origin) rel) rel (into [origin] rel)))
      [origin])))

(defn- tile-block-id-at
  [world-obj x y z]
  (when-let [te (world/world-get-tile-entity* world-obj (pos/create-block-pos x y z))]
    (get-tile-block-id te)))

(defn- lexicographic-min-pos
  [positions]
  (first (sort-by (fn [[x y z]] [x y z]) positions)))

(defn- current-pos-xyz
  [tile]
  (when tile
    (try
      (let [bp (pos/position-get-block-pos tile)]
        [(long (pos/pos-x bp))
         (long (pos/pos-y bp))
         (long (pos/pos-z bp))])
      (catch Exception _
        nil))))

(defn- canonical-origin-pos
  "Derive canonical origin from actual world structure, so only one BE renders.

  This guards against incomplete sub-id initialization where every part defaults
  to sub-id 0 and all parts try to render the full model."
  [tile state block-spec]
  (let [world-obj (pbe/be-get-level tile)
        block-id (or (get-tile-block-id tile) (:block-id state))
        direction (normalize-direction (:direction state :north))
        cur (current-pos-xyz tile)]
    (when (and world-obj block-id cur (:multi-block? block-spec))
      (let [[cx cy cz] cur
            rotated (mapv #(rotate-rel-pos % direction) (spec-relative-positions block-spec))
            candidates (for [[rx ry rz] rotated]
                         [(- cx rx) (- cy ry) (- cz rz)])
            valid? (fn [[ox oy oz]]
                     (every?
                      (fn [[rx ry rz]]
                        (= block-id (tile-block-id-at world-obj (+ ox rx) (+ oy ry) (+ oz rz))))
                      rotated))
            valids (filter valid? candidates)]
        (when (seq valids)
          (lexicographic-min-pos valids))))))

;; ============================================================================
;; Direction to Rotation Mapping
;; ============================================================================

(def direction-rotations
  "Map of direction keywords to Y-axis rotation angles in degrees"
  {:north 180.0
   :south 0.0
   :east 90.0
   :west -90.0})

(defn get-rotation-angle
  "Get rotation angle for a direction
  
  Args:
  - direction: keyword (:north, :south, :east, :west)
  
  Returns: double - rotation angle in degrees"
  [direction]
  (get direction-rotations direction 0.0))

;; ============================================================================
;; Pivot Offset Calculation
;; ============================================================================

(def pivot-offsets
  "Pivot offsets for 2x2x2 multiblock by direction
  
  Format: {direction [x-offset z-offset]}"
  {:north [0.0 0.0]
   :south [1.0 1.0]
  :east [0.0 1.0]
  :west [1.0 0.0]})

(defn get-pivot-offset
  "Get pivot offset for multiblock origin
  
  Args:
  - direction: keyword (:north, :south, :east, :west)
  
  Returns: [x-offset z-offset]"
  [direction]
  (get pivot-offsets direction [0.0 0.0]))

;; ============================================================================
;; Rotation Center Offset
;; ============================================================================

(def rotation-centers
  "Rotation center offsets by direction
  
  These offsets place the rotation pivot at the center of the multiblock.
  Format: {direction [x-offset y-offset z-offset]}"
  {:north [0.5 0.0 0.5]
   :south [0.5 0.0 0.5]
   :east [0.5 0.0 0.5]
   :west [0.5 0.0 0.5]})

(defn get-rotation-center
  "Get rotation center offset
  
  Args:
  - direction: keyword
  
  Returns: [x-offset y-offset z-offset]"
  [direction]
  (get rotation-centers direction [0.5 0.0 0.5]))

(defn- direction->rotation-center
  "Rotate base [x y z] center into legacy BlockMulti direction-space.

  Legacy formulas (for a base center [x y z]):
  - north: [ x  y  z]
  - south: [-x  y -z]
  - west:  [ z  y -x]
  - east:  [-z  y  x]"
  [[x y z] direction]
  (case direction
    :south [(- x) y (- z)]
    :west [z y (- x)]
    :east [(- z) y x]
    [x y z]))

;; ============================================================================
;; Multiblock Render Check
;; ============================================================================

(defn should-render-multiblock?
  "Check if this tile should render the multiblock
  
  Only renders if:
  - Tile has :sub-id field
  - sub-id is 0 (origin block)
  
  Args:
  - tile: TileEntity with :sub-id field
  
  Returns: boolean"
  [tile]
  (let [state (if (map? tile)
                tile
                (or (pbe/get-custom-state tile) {}))]
    (and (map? state)
         (contains? state :sub-id)
         (zero? (long (:sub-id state)))
         (if-let [block-id (or (get-tile-block-id tile) (:block-id state))]
           (if-let [block-spec (registry-metadata/get-block-spec block-id)]
             (if-let [canonical (canonical-origin-pos tile state block-spec)]
               (= canonical (current-pos-xyz tile))
               true)
             true)
           true))))

;; ============================================================================
;; Main Render Function
;; ============================================================================

 (defn render-multiblock-tesr
  "Generic multiblock TESR render function.

  Applies pivot + rotation center offsets using the provided `pose-stack`,
  rotates according to block direction, and calls `render-fn` with the
  rendering context.

  Args:
  - tile: TileEntity with :sub-id and :direction fields
  - render-fn: (fn [tile partial-ticks pose-stack buffer-source packed-light packed-overlay] ...)
  - partial-ticks, pose-stack, buffer-source, packed-light, packed-overlay: rendering context
  "
  [tile render-fn partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (when (should-render-multiblock? tile)
    (try
      (let [state (if (map? tile)
                    tile
                    (or (pbe/get-custom-state tile) {}))
            block-id (or (get-tile-block-id tile)
             (:block-id state))
            block-spec (when block-id
             (registry-metadata/get-block-spec block-id))
            direction (normalize-direction (:direction state :north))
            [pivot-x pivot-z] (get-pivot-offset direction)
            raw-rot-center (or (:multi-block-rotation-center block-spec)
                   (get-rotation-center direction))
            [rot-x rot-y rot-z] (direction->rotation-center raw-rot-center direction)
            rotation (get-rotation-angle direction)
            tx (+ pivot-x rot-x)
            ty rot-y
            tz (+ pivot-z rot-z)]
        (pose/push-pose pose-stack)
        (try
          (pose/translate pose-stack (double tx) (double ty) (double tz))
          ;; Delegate rotation to platform-registered implementation (mcmod must
          ;; not reference Minecraft classes). Platform adapters (forge/fabric)
          ;; should call `cn.li.client.render.pose/register-y-rotation!`
          ;; with a function that applies rotation to the passed `pose-stack`.
          (pose/apply-y-rotation pose-stack rotation)
          (render-fn tile partial-ticks pose-stack buffer-source packed-light packed-overlay)
          (finally
            (pose/pop-pose pose-stack))))
      (catch Exception e
        (log/error "Error rendering multiblock:"(ex-message e))
        (.printStackTrace e)))))

;; ============================================================================
;; Usage Example
;; ============================================================================

;; In platform-specific TESR implementation:
;;
;; (defn render-method [this te x y z partial-ticks ...]
;;   (multiblock-helper/render-multiblock-tesr
;;     te x y z
;;     (fn [tile]
;;       ;; Your rendering code here
;;       (my-renderer/render-at-origin tile))))
