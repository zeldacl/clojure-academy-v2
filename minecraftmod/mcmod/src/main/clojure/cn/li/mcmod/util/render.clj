(ns cn.li.mcmod.util.render
  "Rendering utilities - texture helpers.

   The texture binder is installed during client bootstrap and published as a
   direct function root so bind-texture has no Framework/map lookup on the
   render path."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(def ^:private render-path [:service :render-runtime])
(def ^:private texture-binder-fn nil)
(def ^:private texture-binder-warned? (atom false))

(defn- default-state []
  {:texture-binder nil :texture-binder-warned false})

(defn- update-render-state! [k v]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in render-path
           (fn [current] (assoc (or current (default-state)) k v))))
  nil)

(defn reset-render-runtime-state-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in render-path (default-state)))
  (alter-var-root #'texture-binder-fn (constantly nil))
  (reset! texture-binder-warned? false)
  nil)

(defn register-texture-binder! [binder-fn]
  (update-render-state! :texture-binder binder-fn)
  (alter-var-root #'texture-binder-fn (constantly binder-fn))
  (reset! texture-binder-warned? false)
  nil)

(defn get-render-time []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn bind-texture
  [texture]
  (if-let [binder texture-binder-fn]
    (binder texture)
    (when (compare-and-set! texture-binder-warned? false true)
      (log/warn "Texture binder not registered; skipping bind" texture))))
