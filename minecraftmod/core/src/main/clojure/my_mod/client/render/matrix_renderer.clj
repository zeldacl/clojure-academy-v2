(ns my-mod.client.render.matrix-renderer
  "Wireless Matrix block renderer - multiblock TESR
  
  Renders the 2x2x2 matrix structure with:
  - Static base (Main) and core (Core) parts
  - Animated shield plates (rotating and floating)
  
  Platform-agnostic rendering logic. Platform-specific TESR classes
  should be defined in forge/fabric modules using gen-class."
  (:require [my-mod.client.resources :as res]
            [my-mod.client.obj :as obj]
            [my-mod.util.render :as render]
            [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.client.render.multiblock-helper :as mb-helper]
            [my-mod.block.wireless-matrix :as wm]))

;; ============================================================================
;; Resources (loaded once on initialization)
;; ============================================================================

(defonce model 
  (delay (res/load-obj-model "matrix")))

(defonce texture 
  (delay (res/texture-location "models/matrix")))

;; ============================================================================
;; Rendering Functions
;; ============================================================================

(defn render-base
  "Render static base and core parts
  
  Parts:
  - Main: Base structure
  - Core: Central core component
  
  Args:
  - tile: TileMatrix instance"
  [tile]
  (obj/render-part! @model "Main")
  (obj/render-part! @model "Core"))

(defn render-shields
  "Render animated shield plates
  
  Animation effects:
  - Rotation: Each plate rotates around Y axis at 50°/sec
  - Float: Vertical sine wave oscillation (amplitude: 0.1 blocks)
  - Phase offset: 40° between adjacent plates
  
  Visibility:
  - Only renders if matrix is working: plateCount=3 AND coreLevel>0
  
  Args:
  - tile: TileMatrix instance with @plate-count"
  [tile]
  (let [;; Read state from tile
        plate-count (int @(:plate-count tile))
        core-level (wm/get-core-level tile)
        
        ;; Only show shields if fully operational (3 plates + core)
        active-plates (if (and (= plate-count 3) (> core-level 0)) 
                        3 
                        0)
        
        ;; Animation parameters
        time (render/get-render-time)
        dtheta (/ 360.0 (max active-plates 1))  ; degrees per plate
        phase (mod (* time 50.0) 360.0)          ; rotation phase
        ht-phase-offset 40.0]                    ; height phase offset
    
    ;; Render each active shield
    (dotimes [i active-plates]
      (render/with-matrix
        ;; Vertical floating animation: y = 0.1 * sin(1.111*t + 40*i)
        (let [float-height 0.1
              y-offset (* float-height 
                         (Math/sin (+ (* time 1.111) 
                                     (* ht-phase-offset i))))]
          (render/gl-translate 0.0 y-offset 0.0))
        
        ;; Rotation animation: rotate around Y axis
        (render/gl-rotate (+ phase (* dtheta i)) 0.0 1.0 0.0)
        
        ;; Render shield part
        (obj/render-part! @model "Shield")))))

(defn render-at-origin
  "Main render function - renders complete matrix at multiblock origin
  
  Coordinate system:
  - Origin is at the multiblock pivot (handled by RenderBlockMulti)
  - Rotation applied by parent renderer based on direction
  
  Args:
  - tile: TileMatrix instance
  
  Returns: nil"
  [tile]
  (render/with-matrix
    ;; Bind texture
    (render/bind-texture @texture)
    
    ;; Render components
    (render-base tile)
    (render-shields tile)))

;; ============================================================================
;; TESR API Implementation
;; ============================================================================

;; Implement ITileEntityRenderer protocol for TileMatrix
(extend-protocol tesr-api/ITileEntityRenderer
  my_mod.block.wireless_matrix.TileMatrix
  (tesr-api/render-tile [tile-entity x y z]
    ;; Delegate to multiblock helper for coordinate transformation
    (mb-helper/render-multiblock-tesr
      tile-entity
      x y z
      render-at-origin)))

;; Register the Matrix renderer
(defn register!
  "Register this renderer for TileMatrix tiles
  
  Should be called during platform initialization."
  []
  (tesr-api/register-tile-renderer! 
    my_mod.block.wireless_matrix.TileMatrix
    (proxy [Object] []
      (render-tile [tile x y z]
        (render-at-origin tile)))))

;; ============================================================================
;; Platform Integration Notes
;; ============================================================================

;; Platform-specific TESR classes have been created in:
;;
;; Forge 1.16.5:
;;   forge-1.16.5/src/.../my_mod/forge1165/client/render/matrix_tesr.clj
;;   - Generates: my_mod.forge1165.client.render.MatrixTESR
;;   - Extends: platform tile-entity renderer base class
;;
;; Fabric 1.20.1:
;;   fabric-1.20.1/src/.../my_mod/fabric1201/client/render/matrix_renderer.clj
;;   - Generates: my_mod.fabric1201.client.render.MatrixBlockEntityRenderer
;;   - Implements: platform block-entity renderer interface
;;
;; Both delegate to:
;;   - multiblock-helper/render-multiblock-tesr (coordinate transformation)
;;   - matrix-renderer/render-at-origin (actual rendering)

;; ============================================================================
;; Design Notes
;; ============================================================================

;; Multiblock Rendering Flow:
;; 1. Minecraft calls render() on platform-specific TESR
;; 2. RenderBlockMulti.render() checks if this is the origin block (subID=0)
;; 3. If origin, translates to pivot and rotates based on direction
;; 4. Platform TESR calls render-at-origin (this function)
;; 5. Our code renders model parts at (0,0,0) in local coordinates
;;
;; Animation Timing:
;; - get-render-time returns seconds since game start
;; - Shields rotate at 50°/sec (full rotation every 7.2 seconds)
;; - Vertical oscillation period: ~5.65 seconds (1.111 rad/sec)
;; - Phase offset creates "wave" effect across 3 plates
;;
;; Model Parts (from matrix.obj):
;; - "Main": Base structure
;; - "Core": Central core
;; - "Shield": Rotating plate (rendered 3 times if active)


