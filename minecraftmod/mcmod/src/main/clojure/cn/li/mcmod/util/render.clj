(ns cn.li.mcmod.util.render
  "Rendering utilities - OpenGL and texture helpers"
  (:require [cn.li.mcmod.util.log :as log]))

(defn- default-render-runtime-state []
  {:gl-ops nil
   :texture-binder nil
   :texture-binder-warned false})

(defn create-render-runtime
  ([] (create-render-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.util.render/runtime ::render-runtime
    :state* (or state* (atom (default-render-runtime-state)))}))

(defonce ^:private installed-render-runtime
  (create-render-runtime))

(def ^:dynamic *render-runtime*
  installed-render-runtime)

(defn- render-state-atom []
  (:state* *render-runtime*))

(defn- render-state-snapshot []
  @(render-state-atom))

(defn reset-render-runtime-state-for-test!
  "Reset render runtime state. Intended for tests."
  []
  (reset! (render-state-atom) (default-render-runtime-state))
  nil)

(defn register-texture-binder!
  "Register platform/client texture bind function.

  Signature: (fn [texture] ... )"
  [binder-fn]
  (swap! (render-state-atom) assoc :texture-binder binder-fn)
  nil)

(defn register-gl-ops!
  "Register platform/client GL11 operation fns (see cn.li.mc1201.client.gl-ops/ops-map)."
  [ops-map]
  (swap! (render-state-atom) assoc :gl-ops ops-map)
  nil)

(defn- gl-op [op-key & args]
  (when-let [ops (:gl-ops (render-state-snapshot))]
    (when-let [f (get ops op-key)]
      (apply f args))))

(defn get-render-time
  "Get current render time in seconds

  Returns: double - seconds since game start"
  []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn gl-push-matrix
  []
  (gl-op :push-matrix))

(defn gl-pop-matrix
  []
  (gl-op :pop-matrix))

(defn gl-translate
  [x y z]
  (gl-op :translate x y z))

(defn gl-rotate
  [angle x y z]
  (gl-op :rotate angle x y z))

(defn gl-scale
  [x y z]
  (gl-op :scale x y z))

(defn gl-begin-triangles
  []
  (gl-op :begin-triangles))

(defn gl-end
  []
  (gl-op :end))

(defn gl-normal
  [x y z]
  (gl-op :normal x y z))

(defn gl-tex-coord
  [u v]
  (gl-op :tex-coord u v))

(defn gl-vertex
  [x y z]
  (gl-op :vertex x y z))

(defn bind-texture
  [texture]
  (if-let [binder (:texture-binder (render-state-snapshot))]
    (binder texture)
    (let [warned? (:texture-binder-warned (render-state-snapshot))]
      (when-not warned?
        (swap! (render-state-atom) assoc :texture-binder-warned true)
        (log/warn "Texture binder not registered; skipping bind" texture)))))

(defmacro with-matrix
  [& body]
  `(do
     (gl-push-matrix)
     (try
       ~@body
       (finally
         (gl-pop-matrix)))))
