(ns my-mod.client.render.multiblock-helper
  "Multiblock TESR rendering helper - platform-agnostic
  
  Provides coordinate transformation logic for multiblock structures.
  Replaces functionality of cn.lambdalib2.multiblock.RenderBlockMulti."
  (:require [my-mod.util.render :as render]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Direction to Rotation Mapping
;; ============================================================================

(def direction-rotations
  "Map of direction keywords to Y-axis rotation angles in degrees"
  {:north 0.0
   :south 180.0
   :east 90.0
   :west 270.0})

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
   :east [1.0 0.0]
   :west [0.0 1.0]})

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
  (and tile
       (contains? tile :sub-id)
       (= (:sub-id tile) 0)))

;; ============================================================================
;; Main Render Function
;; ============================================================================

(defn render-multiblock-tesr
  "Generic multiblock TESR render function
  
  Handles coordinate transformation for multiblock structures:
  1. Checks if this is the origin block (sub-id = 0)
  2. Translates to world position + pivot offset + rotation center
  3. Rotates based on direction
  4. Calls render function
  
  Args:
  - tile: TileEntity with :sub-id and :direction fields
  - x: double - render X position
  - y: double - render Y position
  - z: double - render Z position
  - render-fn: (fn [tile] ...) - function to call at origin
  
  Returns: nil"
  [tile x y z render-fn]
  (when (should-render-multiblock? tile)
    (try
      (let [direction (:direction tile)
            [pivot-x pivot-z] (get-pivot-offset direction)
            [rot-x rot-y rot-z] (get-rotation-center direction)
            rotation (get-rotation-angle direction)]
        
        (render/with-matrix
          ;; Translate to world position + offsets
          (render/gl-translate
            (+ x pivot-x rot-x)
            (+ y rot-y)
            (+ z pivot-z rot-z))
          
          ;; Rotate around Y axis based on direction
          (render/gl-rotate rotation 0.0 1.0 0.0)
          
          ;; Call render function at transformed origin
          (render-fn tile)))
      
      (catch Exception e
        (log/error "Error rendering multiblock:" (.getMessage e))
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
