(ns cn.li.mcmod.platform.runtime-interop
  "Canonical runtime-side platform interop for world/player queries."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IRuntimeInterop
  (get-player-view [this player-uuid])
  (get-player-main-hand-item [this player-uuid])
  (get-block-entity-at [this world-id x y z]))

(defn install-runtime-interop!
  [impl label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :runtime-interop] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :runtime-interop])))
(defn current [] (get-in @(fw/fw-atom) [:platform :runtime-interop]))
(defn call-with-runtime [rt f] (f rt))

(defn get-player-view* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :runtime-interop])]
    (get-player-view rt player-uuid)))
(defn get-player-main-hand-item* [player-uuid]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :runtime-interop])]
    (get-player-main-hand-item rt player-uuid)))
(defn get-block-entity-at* [world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :runtime-interop])]
    (get-block-entity-at rt world-id x y z)))
