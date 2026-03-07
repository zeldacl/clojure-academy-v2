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
  [_tile]
  (render/with-matrix
    (render/bind-texture @texture)
    (render/gl-rotate 90.0 0.0 1.0 0.0)
    (render/gl-scale 0.014 0.014 0.014)
    (obj/render-all! @model)))

(defn register!
  "Register SolarGen renderer by block-id for generic ScriptedBlockEntity."
  []
  (tesr-api/register-scripted-tile-renderer!
    "solar-gen"
    (reify tesr-api/ITileEntityRenderer
      (render-tile [_ _tile-entity x y z]
        (render/with-matrix
          (render/gl-translate x y z)
          (render-at-origin nil))))))

