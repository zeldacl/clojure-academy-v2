(ns cn.li.ac.block.solar-gen.render
  "CLIENT-ONLY: Solar generator block entity renderer."
  (:require [cn.li.ac.block.machine.render-runtime :as machine-render-runtime]
            [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.util.log :as log]))

(def ^:private solar-render-resource-lock (Object.))
(def ^:private ^:dynamic *solar-model* nil)
(def ^:private ^:dynamic *solar-texture* nil)

(def ^:private solar-model
  (machine-render-runtime/lazy-resource solar-render-resource-lock #'*solar-model*
                                        #(obj/bake-obj-model (res/load-obj-model "solar")
                                                             {:skip-flat-bottom-plane? true
                                                              :bottom-plane-epsilon 0.0008})))

(def ^:private solar-texture
  (machine-render-runtime/lazy-resource solar-render-resource-lock #'*solar-texture*
                                        #(res/texture-location "models/solar")))

(defn render-at-origin
  [_tile pose-stack buffer-source packed-light packed-overlay]
  (pose/push-pose pose-stack)
  (try
    (let [_ (pose/translate pose-stack (double 0.5) (double 0.02) (double 0.5))
          _ (pose/apply-y-rotation pose-stack 90.0)
          _ (pose/scale pose-stack (float 0.014) (float 0.014) (float 0.014))
          vc (rb/get-solid-buffer buffer-source (solar-texture))]
      (obj/render-baked-all! (solar-model) pose-stack vc packed-light packed-overlay))
    (finally
      (pose/pop-pose pose-stack))))

(defn register!
  []
  (tesr-api/register-scripted-tile-renderer!
    "solar-gen"
    {:render-tile (fn [_tile-entity _partial-ticks pose-stack buffer-source packed-light packed-overlay]
                     (try
                       (render-at-origin nil pose-stack buffer-source packed-light packed-overlay)
                       (catch Exception e
                         (log/error "Error in solar renderer:" (ex-message e))
                         (.printStackTrace e))))}))

(defn init!
  []
  (machine-render-runtime/register-client-renderer-init! 'cn.li.ac.block.solar-gen.render/register!))
