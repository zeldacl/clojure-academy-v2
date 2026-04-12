(ns cn.li.ac.block.wireless-matrix.render
  "CLIENT-ONLY: Wireless matrix block entity renderer.

  This namespace must be loaded via side-checked requiring-resolve from the
  platform layer. It uses platform-agnostic protocols only, no direct Minecraft
  class imports.

  Renders the 2x2x2 matrix structure with:
  - Static base (Main) and core (Core) parts
  - Animated shield plates (rotating and floating)

  Platform-agnostic rendering logic. Platform-specific TESR classes
  should be defined in forge/fabric modules using gen-class."
  (:require [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.multiblock-helper :as mb-helper]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.obj-tesr-common :as obj-tesr]
            [cn.li.ac.block.wireless-matrix.block :as wm]))

;; ============================================================================
;; Resources (loaded once on initialization)
;; ============================================================================

(defonce model 
  (delay (res/load-obj-model "matrix")))

(defonce texture 
  (delay (res/texture-location "models/matrix")))

(defonce ^:private last-shield-hw-state
  (atom nil))

;; ============================================================================
;; Rendering Functions
;; ============================================================================

(defn render-base
  "Render static base and core parts using buffered VertexConsumer

  Args:
  - tile: TileMatrix instance
  - pose-stack, vertex-consumer, packed-light, packed-overlay"
  [_tile pose-stack vertex-consumer packed-light packed-overlay]
  (obj-tesr/render-obj-parts! @model ["Main" "Core"] pose-stack vertex-consumer packed-light packed-overlay))

(defn render-shields
  "Render animated shield plates using PoseStack and buffered vertex consumer.

  Args:
  - tile: TileMatrix
  - partial-ticks, pose-stack, vertex-consumer, packed-light, packed-overlay"
  [tile _partial-ticks pose-stack vertex-consumer packed-light packed-overlay]
  (let [plate-count (int (wm/get-plate-count tile))
        core-level (wm/get-core-level tile)
        active-plates (if (and (= plate-count 3) (> core-level 0)) 3 0)
        time (render/get-render-time)
        dtheta (/ 360.0 (max active-plates 1))
        phase (mod (* time 50.0) 360.0)
        ht-phase-offset 40.0]
    (let [hw-state {:plate-count plate-count :core-level core-level :active-plates active-plates}]
      (when (not= hw-state @last-shield-hw-state)
        (reset! last-shield-hw-state hw-state)))
    (dotimes [i active-plates]
      (pose/push-pose pose-stack)
      (try
        (let [float-height 0.1
              y-offset (* float-height (Math/sin (+ (* time 1.111) (* ht-phase-offset i))))]
          (pose/translate pose-stack (double 0.0) (double y-offset) (double 0.0))
          (pose/apply-y-rotation pose-stack (+ phase (* dtheta i)))
          (obj/render-part-consumer @model "Shield" pose-stack vertex-consumer packed-light packed-overlay))
        (finally
          (pose/pop-pose pose-stack))))))

(defn render-at-origin
  "Main render function - renders complete matrix at multiblock origin

  Args:
  - tile: TileMatrix instance
  - partial-ticks, pose-stack, buffer-source, packed-light, packed-overlay"
  [tile partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (obj-tesr/translate-obj-y-lift! pose-stack)
  (obj-tesr/with-solid-vc-and-obj-bindings! buffer-source @texture
    (fn [vc]
      (render-base tile pose-stack vc packed-light packed-overlay)
      (render-shields tile partial-ticks pose-stack vc packed-light packed-overlay))))

;; ============================================================================
;; TESR API Implementation
;; ============================================================================

;; Register the Matrix renderer
(defn register!
  "Register this renderer for wireless-matrix scripted block entities.
  Should be called during platform initialization."
  []
  (let [renderer
        (reify tesr-api/ITileEntityRenderer
          (render-tile [_ tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
            (mb-helper/render-multiblock-tesr
             tile-entity
             render-at-origin
             partial-ticks pose-stack buffer-source packed-light packed-overlay)))]
    (tesr-api/register-scripted-tile-renderer! "wireless-matrix" renderer)
    ;; Multiblock part tiles share the same BlockEntity type and renderer dispatch.
    (tesr-api/register-scripted-tile-renderer! "wireless-matrix-part" renderer)))

(defonce ^:private matrix-renderer-installed? (atom false))

(defn init!
  "Entry for `ac.registry.hooks/load-all-client-renderers!` (matches solar-gen pattern)."
  []
  (when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
    (when (compare-and-set! matrix-renderer-installed? false true)
      (register-fn register!))))

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
