(ns cn.li.ac.block.solar-gen.render
  "CLIENT-ONLY: Solar generator block entity renderer.

  This namespace must be loaded via side-checked requiring-resolve from the
  platform layer. It uses platform-agnostic protocols only, no direct Minecraft
  class imports.

  Ported from AcademyCraft's RenderSolarGen:
  - Rotate 90 degrees around Y
  - Scale by 0.014
  - Render solar.obj with models/solar texture"
  (:require [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack]))

(defonce model (delay (res/load-obj-model "solar")))
(defonce texture (delay (res/texture-location "models/solar")))

(defn render-at-origin
  [_tile pose-stack buffer-source packed-light packed-overlay]
  (let [^PoseStack pose-stack pose-stack]
    (.pushPose pose-stack)
    (try
      (let [;; rotate 90 degrees around Y
            ;; Lift slightly above the support block top to avoid origin-cell
            ;; depth conflict that makes the block below appear transparent.
            _ (.translate pose-stack (double 0.5) (double 0.02) (double 0.5))
            _ (pose/apply-y-rotation pose-stack 90.0)
            ;; scale
            _ (.scale pose-stack (float 0.014) (float 0.014) (float 0.014))
            ;; Use culled solid buffer so the model underside doesn't overlay the
            ;; supporting block's top texture.
            vc (rb/get-solid-buffer buffer-source @texture)]
        (binding [obj/*skip-flat-bottom-plane* true
                  obj/*bottom-plane-epsilon* 0.0008]
          (obj/render-all! @model pose-stack vc packed-light packed-overlay)))
      (finally
        (.popPose pose-stack)))))

(defn register!
  "Register SolarGen renderer by block-id for generic ScriptedBlockEntity."
  []
  (tesr-api/register-scripted-tile-renderer!
    "solar-gen"
    (reify tesr-api/ITileEntityRenderer
      (render-tile [_ _tile-entity _partial-ticks pose-stack buffer-source packed-light packed-overlay]
        (try
          (render-at-origin nil pose-stack buffer-source packed-light packed-overlay)
          (catch Exception e
            (log/error "Error in solar renderer:"(ex-message e))
            (.printStackTrace e)))))))

(defonce ^:private solar-renderer-installed? (atom false))

(defn init!
  []
  (when (compare-and-set! solar-renderer-installed? false true)
    (when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
      (register-fn register!))
    (log/info "Registered solar renderer for block-id" "solar-gen")))
