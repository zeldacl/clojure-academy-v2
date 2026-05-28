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
            [cn.li.mcmod.util.log :as log]))

(def ^:private solar-render-resource-lock
  (Object.))

(def ^:private ^:dynamic *solar-model*
  nil)

(def ^:private ^:dynamic *solar-texture*
  nil)

(defn- solar-model
  []
  (or (var-get #'*solar-model*)
      (locking solar-render-resource-lock
        (or (var-get #'*solar-model*)
            (let [m (res/load-obj-model "solar")]
              (alter-var-root #'*solar-model* (constantly m))
              m)))))

(defn- solar-texture
  []
  (or (var-get #'*solar-texture*)
      (locking solar-render-resource-lock
        (or (var-get #'*solar-texture*)
            (let [t (res/texture-location "models/solar")]
              (alter-var-root #'*solar-texture* (constantly t))
              t)))))

(defn render-at-origin
  [_tile pose-stack buffer-source packed-light packed-overlay]
  (pose/push-pose pose-stack)
  (try
    (let [;; rotate 90 degrees around Y
          ;; Lift slightly above the support block top to avoid origin-cell
          ;; depth conflict that makes the block below appear transparent.
          _ (pose/translate pose-stack (double 0.5) (double 0.02) (double 0.5))
          _ (pose/apply-y-rotation pose-stack 90.0)
          ;; scale
          _ (pose/scale pose-stack (float 0.014) (float 0.014) (float 0.014))
          ;; Use culled solid buffer so the model underside doesn't overlay the
          ;; supporting block's top texture.
          vc (rb/get-solid-buffer buffer-source (solar-texture))]
      (binding [obj/*skip-flat-bottom-plane* true
                obj/*bottom-plane-epsilon* 0.0008]
        (obj/render-all! (solar-model) pose-stack vc packed-light packed-overlay)))
    (finally
      (pose/pop-pose pose-stack))))

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

(def ^:private solar-renderer-guard-lock
  (Object.))

(def ^:private ^:dynamic *solar-renderer-installed?*
  false)

(defn init!
  []
  (when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
    (when-not (var-get #'*solar-renderer-installed?*)
      (locking solar-renderer-guard-lock
        (when-not (var-get #'*solar-renderer-installed?*)
          (register-fn register!)
          (alter-var-root #'*solar-renderer-installed?* (constantly true))
          (log/info "Registered solar renderer for block-id" "solar-gen"))))))
