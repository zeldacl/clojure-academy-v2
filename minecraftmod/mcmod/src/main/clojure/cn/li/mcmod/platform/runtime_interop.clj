(ns cn.li.mcmod.platform.runtime-interop
  "Runtime-side platform interop for world/player queries via Framework function map.

   Impl stored at [:platform :runtime-interop]."
  (:require [cn.li.mcmod.framework :as fw]))

(def runtime-interop-keys
  #{:get-player-view :get-player-main-hand-item :get-block-entity-at})

(defn install-runtime-interop!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :runtime-interop] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :runtime-interop])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :runtime-interop]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn get-player-view*            [player-uuid]    (call :get-player-view player-uuid))
(defn get-player-main-hand-item*  [player-uuid]    (call :get-player-main-hand-item player-uuid))
(defn get-block-entity-at*        [world-id x y z] (call :get-block-entity-at world-id x y z))
