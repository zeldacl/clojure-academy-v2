(ns cn.li.mcmod.platform.raycast
  "Raycasting (line-of-sight) operations via Framework function map.

   Impl stored at [:platform :raycast]."
  (:require [cn.li.mcmod.framework :as fw]))

(def raycast-keys
  #{:raycast-blocks :raycast-entities :raycast-combined
    :get-player-look-vector :raycast-from-player})

(defn install-raycast!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :raycast] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :raycast])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :raycast]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn raycast-blocks*        [world-id sx sy sz dx dy dz max-dist] (call :raycast-blocks world-id sx sy sz dx dy dz max-dist))
(defn raycast-entities*      [world-id sx sy sz dx dy dz max-dist] (call :raycast-entities world-id sx sy sz dx dy dz max-dist))
(defn raycast-combined*      [world-id sx sy sz dx dy dz max-dist] (call :raycast-combined world-id sx sy sz dx dy dz max-dist))
(defn get-player-look-vector* [player-uuid]                         (call :get-player-look-vector player-uuid))
(defn raycast-from-player*   [player-uuid max-dist living-only?]    (call :raycast-from-player player-uuid max-dist living-only?))
