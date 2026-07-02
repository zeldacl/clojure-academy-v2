(ns cn.li.mcmod.platform.block-manipulation
  "Protocol for breaking and modifying blocks."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IBlockManipulation
  (break-block! [this player-id world-id x y z drop?]
    [this player-id world-id x y z drop? fortune-level])
  (set-block! [this world-id x y z block-id])
  (get-block [this world-id x y z])
  (get-block-hardness [this world-id x y z])
  (can-break-block? [this player-id world-id x y z])
  (find-blocks-in-line [this world-id x1 y1 z1 dx dy dz max-distance])
  (liquid-block? [this world-id x y z])
  (farmland-block? [this world-id x y z]))

(defn install-block-manipulation!
  [impl label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :block-manipulation] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :block-manipulation])))
(defn current [] (get-in @(fw/fw-atom) [:platform :block-manipulation]))
(defn call-with-runtime [rt f] (f rt))

(defn break-block!*
  ([player-id world-id x y z drop?]
   (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
     (break-block! rt player-id world-id x y z drop?)))
  ([player-id world-id x y z drop? fortune-level]
   (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
     (break-block! rt player-id world-id x y z drop? fortune-level))))
(defn set-block!* [world-id x y z block-id]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (set-block! rt world-id x y z block-id)))
(defn get-block* [world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (get-block rt world-id x y z)))
(defn get-block-hardness* [world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (get-block-hardness rt world-id x y z)))
(defn can-break-block?* [player-id world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (can-break-block? rt player-id world-id x y z)))
(defn find-blocks-in-line* [world-id x1 y1 z1 dx dy dz max-distance]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (find-blocks-in-line rt world-id x1 y1 z1 dx dy dz max-distance)))
(defn liquid-block?* [world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (liquid-block? rt world-id x y z)))
(defn farmland-block?* [world-id x y z]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (farmland-block? rt world-id x y z)))

(defn break-block-with-fortune!*
  [player-id world-id x y z drop? fortune-level]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :block-manipulation])]
    (break-block! rt player-id world-id x y z drop? fortune-level)))
