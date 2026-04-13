(ns cn.li.ac.block.developer.render
  "CLIENT-ONLY: Developer station TESR (normal / advanced) using OBJ models.

  Uses the same solid-buffer + OBJ bottom-plane defaults as wireless-matrix
  (`cn.li.mcmod.client.render.obj-tesr-common`). Model-local center + scale,
  then matrix-style Y lift in block space.

  Loaded only from client init via hooks; uses mcmod protocols only."
  (:require [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.multiblock-helper :as mb-helper]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.obj-tesr-common :as obj-tesr]
            [cn.li.mcmod.util.log :as log]))

(defn- obj-controller-anchor-offset
  "Return `[ax miny az]` anchoring the mesh to the controller cell center.

  Developer is a `1x3` multiblock laid out along `+Z`, with the controller in
  the first cell. Using full AABB center on Z shifts the whole model toward the
  footprint center; instead we anchor to the first-cell center derived from the
  mesh extent."
  [model]
  (when-let [verts (seq (:vertices model))]
    (let [pos (map :pos verts)
          xs (map #(double (:x %)) pos)
          ys (map #(double (:y %)) pos)
          zs (map #(double (:z %)) pos)
          minx (reduce min xs)
          maxx (reduce max xs)
          miny (reduce min ys)
          minz (reduce min zs)
          maxz (reduce max zs)
          x-cells 1.0
          z-cells 3.0
          ax (+ minx (/ (- maxx minx) (* 2.0 x-cells)))
          az (+ minz (/ (- maxz minz) (* 2.0 z-cells)))]
      [ax miny az])))

(defonce ^:private normal-model (delay (res/load-obj-model "developer_normal")))
;; OBJ uses dedicated UV-unwrapped texture, not the tiny block icon tile.
(defonce ^:private normal-tex (delay (res/texture-location "models/developer_normal")))

(defonce ^:private advanced-model (delay (res/load-obj-model "developer_advanced")))
(defonce ^:private advanced-tex (delay (res/texture-location "models/developer_advanced")))

(defn- render-obj-at-origin!
  [model-delay tex-delay _tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (pose/push-pose pose-stack)
  (try
    (let [model @model-delay
          tex @tex-delay
          [ax miny az] (or (obj-controller-anchor-offset model) [0.0 0.0 0.0])
          ;; Re-anchor the mesh so controller cell center maps to local origin.
          _ (pose/translate pose-stack (- ax) (- miny) (- az))
          s (float 0.5)]
      (pose/scale pose-stack s s s)
      ;; Block-space lift after scale (matches matrix: small Y in block units).
      (obj-tesr/translate-obj-y-lift! pose-stack)
      (obj-tesr/with-solid-vc-and-obj-bindings! buffer-source tex
        (fn [vc]
          ;; Developer has many near-coplanar bottom faces; flat-bottom culling
          ;; strips real geometry and reads as a “shattered” mesh. Matrix keeps it on.
          (binding [obj/*skip-flat-bottom-plane* false]
            (obj-tesr/render-obj-parts! model (sort (keys (:faces model))) pose-stack vc packed-light packed-overlay)))))
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
