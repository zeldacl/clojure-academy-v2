(ns cn.li.mcmod.client.render.obj-tesr-common
  "Shared OBJ + solid-buffer conventions for multiblock TESR (e.g. matrix, developer).

  Keeps Y lift, bottom-plane skip binding, and `render-part-consumer` usage aligned across blocks."
  (:require [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]))

(def ^:const ^double default-obj-y-lift
  "Lift in block space; matches matrix TESR (reduces z-fight with support cells)."
  0.02)

(def ^:const ^double default-bottom-plane-epsilon
  "OBJ bottom-plane culling tolerance; matches matrix / solar OBJ renderers."
  0.0008)

(defn translate-obj-y-lift!
  [pose-stack]
  (pose/translate pose-stack (double 0.0) (double default-obj-y-lift) (double 0.0)))

(defn render-obj-parts!
  "Draw named OBJ groups in order. Caller must already apply desired pose + bindings / vc."
  [model part-names pose-stack vertex-consumer packed-light packed-overlay]
  (doseq [part part-names]
    (when (obj/has-part? model part)
      (obj/render-part-consumer model part pose-stack vertex-consumer packed-light packed-overlay))))

(defn render-obj-parts-multi!
  "Like `render-obj-parts!`, but uses `obj/render-part-consumer-multi` so faces
  with `:material` (from `usemtl` + parsed MTL) can bind different textures per
  batch. `material->texture` receives the MTL material name (string)."
  [model part-names buffer-source default-texture material->texture pose-stack packed-light packed-overlay]
  (doseq [part part-names]
    (when (obj/has-part? model part)
      (obj/render-part-consumer-multi
       model part pose-stack buffer-source default-texture packed-light packed-overlay material->texture))))

(defn with-solid-vc-and-obj-bindings!
  "Solid `RenderType` buffer + default OBJ bottom-plane bindings; invokes `(f vertex-consumer)`."
  [buffer-source texture-loc f]
  (let [vc (rb/get-solid-buffer buffer-source texture-loc)]
    (binding [obj/*skip-flat-bottom-plane* true
              obj/*bottom-plane-epsilon* default-bottom-plane-epsilon]
      (f vc))))
