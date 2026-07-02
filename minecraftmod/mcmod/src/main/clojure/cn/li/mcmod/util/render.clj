(ns cn.li.mcmod.util.render
  "Rendering utilities - OpenGL and texture helpers.

  State stored in Framework [:service :render-runtime]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(defn- default-state []
  {:gl-ops nil :texture-binder nil :texture-binder-warned false})

(def ^:private render-path [:service :render-runtime])

(defn- render-state-snapshot []
  (if-let [fw-atom fw/*framework*]
    (get-in @fw-atom render-path (default-state))
    (default-state)))

(defn- update-render-state! [f & args]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in render-path
           (fn [current] (apply f (or current (default-state)) args))))
  nil)

(defn reset-render-runtime-state-for-test!
  []
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom assoc-in render-path (default-state)))
  nil)

(defn register-texture-binder! [binder-fn]
  (update-render-state! assoc :texture-binder binder-fn))

(defn register-gl-ops! [ops-map]
  (update-render-state! assoc :gl-ops ops-map))

(defn- gl-op [op-key & args]
  (when-let [ops (:gl-ops (render-state-snapshot))]
    (when-let [f (get ops op-key)]
      (apply f args))))

(defn get-render-time []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn gl-push-matrix [] (gl-op :push-matrix))
(defn gl-pop-matrix [] (gl-op :pop-matrix))
(defn gl-translate [x y z] (gl-op :translate x y z))
(defn gl-rotate [angle x y z] (gl-op :rotate angle x y z))
(defn gl-scale [x y z] (gl-op :scale x y z))
(defn gl-begin-triangles [] (gl-op :begin-triangles))
(defn gl-end [] (gl-op :end))
(defn gl-normal [x y z] (gl-op :normal x y z))
(defn gl-tex-coord [u v] (gl-op :tex-coord u v))
(defn gl-vertex [x y z] (gl-op :vertex x y z))

(defn bind-texture
  [texture]
  (if-let [binder (:texture-binder (render-state-snapshot))]
    (binder texture)
    (let [warned? (:texture-binder-warned (render-state-snapshot))]
      (when-not warned?
        (update-render-state! assoc :texture-binder-warned true)
        (log/warn "Texture binder not registered; skipping bind" texture)))))

(defmacro with-matrix
  [& body]
  `(do (gl-push-matrix)
       (try ~@body
            (finally (gl-pop-matrix)))))
