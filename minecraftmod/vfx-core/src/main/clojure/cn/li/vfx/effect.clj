(ns cn.li.vfx.effect
  "Small source-first DSL for declaring data-driven VFX descriptors."
  (:require [cn.li.vfx.runtime :as runtime]))

(defmacro defeffect
  "Define a descriptor whose implementation callbacks remain ordinary Clojure.

   The descriptor is deliberately just data plus functions; no platform type or
   renderer is captured by the macro."
  [symbol-name descriptor]
  `(def ~(with-meta symbol-name {:doc "VFX descriptor"})
     (update ~descriptor :id #(or % ~(keyword (str (ns-name *ns*)) (name symbol-name))))))

(defn register-all!
  "Register descriptors and freeze the registry in one composition step."
  [vfx-runtime descriptors]
  (doseq [descriptor descriptors]
    (runtime/register-effect! vfx-runtime descriptor))
  (runtime/freeze-registry! vfx-runtime))
