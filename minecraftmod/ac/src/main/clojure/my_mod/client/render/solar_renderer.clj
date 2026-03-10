(ns my-mod.client.render.solar-renderer
  "Solar Generator block renderer (OBJ via BlockEntityRenderer).

  Ported from AcademyCraft's RenderSolarGen:
  - Rotate 90 degrees around Y
  - Scale by 0.014
  - Render solar.obj with models/solar texture"
  (:require [my-mod.client.resources :as res]
            [my-mod.client.obj :as obj]
            [my-mod.util.render :as render]
            [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.util.log :as log]))

(defonce model (delay (res/load-obj-model "solar")))
(defonce texture (delay (res/texture-location "models/solar")))

(defn render-at-origin
  [_tile pose-stack buffer-source packed-light packed-overlay]
  (do
    (.pushPose pose-stack)
    (try
      (let [;; rotate 90 degrees around Y
            _ (.mulPose pose-stack (.rotationDegrees (.-YP com.mojang.math.Vector3f) (float 90.0)))
            ;; scale
            _ (.scale pose-stack (float 0.014) (float 0.014) (float 0.014))
            ;; Use translucent entity render type to allow alpha in textures.
            vc (.getBuffer buffer-source (net.minecraft.client.renderer.RenderType/entityTranslucent @texture))]
        (obj/render-all! @model pose-stack vc packed-light packed-overlay))
      (finally
        (.popPose pose-stack)))))

(defn register!
  "Register SolarGen renderer by block-id for generic ScriptedBlockEntity."
  []
  (tesr-api/register-scripted-tile-renderer!
    "solar-gen"
    (reify tesr-api/ITileEntityRenderer
      (render-tile [_ _tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
        (log/info "solar renderer invoked for solar-gen" :tile _tile-entity :partial-ticks partial-ticks)
        (try
          (render-at-origin nil pose-stack buffer-source packed-light packed-overlay)
          (catch Exception e
            (log/error "Error in solar renderer:" (.getMessage e))
            (.printStackTrace e)))))))

(log/info "Registered solar renderer for block-id" "solar-gen")

