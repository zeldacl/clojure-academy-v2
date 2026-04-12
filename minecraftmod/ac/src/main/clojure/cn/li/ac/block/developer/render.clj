(ns cn.li.ac.block.developer.render
  "CLIENT-ONLY: Developer station TESR (normal / advanced) using OBJ models.

  Loaded only from client init via hooks; uses mcmod protocols only."
  (:require [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.multiblock-helper :as mb-helper]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private normal-model (delay (res/load-obj-model "developer_normal")))
(defonce ^:private normal-tex (delay (res/texture-location "block/dev_normal")))

(defonce ^:private advanced-model (delay (res/load-obj-model "developer_advanced")))
(defonce ^:private advanced-tex (delay (res/texture-location "block/dev_advanced")))

(defn- render-obj-at-origin!
  [model-delay tex-delay _tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (pose/push-pose pose-stack)
  (try
    (let [;; Footprint pivot + facing already applied by `render-multiblock-tesr`.
          ;; Do not add extra Y rotation here (would stack on block :direction).
          ;; Slight Y lift only to reduce z-fighting with ground.
          _ (pose/translate pose-stack 0.0 0.001 0.0)
          ;; OBJ spans ~6 units on Y; multiblock footprint is 3 blocks tall.
          s (float 0.5)
          _ (pose/scale pose-stack s s s)
          vc (rb/get-solid-buffer buffer-source @tex-delay)]
      (binding [obj/*skip-flat-bottom-plane* true
                obj/*bottom-plane-epsilon* 0.0008]
        (obj/render-all! @model-delay pose-stack vc packed-light packed-overlay)))
    (finally
      (pose/pop-pose pose-stack))))

(defn- make-multiblock-renderer [model-delay tex-delay]
  (reify tesr-api/ITileEntityRenderer
    (render-tile [_ tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
      (mb-helper/render-multiblock-tesr
       tile-entity
       (fn [tile pt ps bs pl po]
         (render-obj-at-origin! model-delay tex-delay tile pt ps bs pl po))
       partial-ticks pose-stack buffer-source packed-light packed-overlay))))

(defn register!
  "Register BER dispatch for developer controller + part block-ids."
  []
  (let [n (make-multiblock-renderer normal-model normal-tex)
        a (make-multiblock-renderer advanced-model advanced-tex)]
    (tesr-api/register-scripted-tile-renderer! "developer-normal" n)
    (tesr-api/register-scripted-tile-renderer! "developer-normal-part" n)
    (tesr-api/register-scripted-tile-renderer! "developer-advanced" a)
    (tesr-api/register-scripted-tile-renderer! "developer-advanced-part" a)))

(defonce ^:private installed? (atom false))

(defn init!
  []
  (when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
    (when (compare-and-set! installed? false true)
      (register-fn register!)
      (log/info "Registered developer OBJ tile renderers (normal + advanced)"))))
