(ns cn.li.ac.ability.effects.block
  (:require [cn.li.mcmod.framework :as fw]))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :block-manipulation])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :block-manipulation]))

(defn- call
  [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn install-destroy-gate!
  [pred]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in [:platform :block-destroy-gate] pred))
  nil)

(defn destroy-allowed?
  []
  (if-let [pred (get-in @(fw/fw-atom) [:platform :block-destroy-gate])]
    (boolean (pred))
    true))

(defn break-block!
  ([player-id world-id x y z drop?]
   (when (destroy-allowed?)
     (call :break-block! player-id world-id x y z drop?)))
  ([player-id world-id x y z drop? fortune-level]
   (when (destroy-allowed?)
     (call :break-block! player-id world-id x y z drop? fortune-level))))

(defn set-block!
  [world-id x y z block-id]
  (call :set-block! world-id x y z block-id))

(defn get-block
  [world-id x y z]
  (call :get-block world-id x y z))

(defn get-block-hardness
  [world-id x y z]
  (call :get-block-hardness world-id x y z))

(defn can-break-block?
  [player-id world-id x y z]
  (call :can-break-block? player-id world-id x y z))

(defn find-blocks-in-line
  [world-id x1 y1 z1 dx dy dz max-distance]
  (call :find-blocks-in-line world-id x1 y1 z1 dx dy dz max-distance))

(defn liquid-block?
  [world-id x y z]
  (call :liquid-block? world-id x y z))

(defn farmland-block?
  [world-id x y z]
  (call :farmland-block? world-id x y z))
