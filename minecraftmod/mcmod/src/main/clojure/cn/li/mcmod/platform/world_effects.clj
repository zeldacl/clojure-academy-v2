(ns cn.li.mcmod.platform.world-effects
  "World-level effects (lightning, explosions, entity/block queries).

   Impl stored in Framework [:platform :world-effects] as a function map.
   No defprotocol, no ThreadLocal."
  (:require [cn.li.mcmod.framework :as fw]))

(def world-effects-keys
  "Expected keys in the world-effects function map."
  #{:spawn-lightning! :create-explosion! :spawn-projectile!
    :find-entities-in-radius :find-entities-in-aabb
    :find-blocks-in-radius :play-sound!})

(defn install-world-effects!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in [:platform :world-effects] impl))
  nil)

(defn available? []
  (boolean (get-in @(fw/fw-atom) [:platform :world-effects])))

(defn current []
  (get-in @(fw/fw-atom) [:platform :world-effects]))

(defn call-with-runtime [rt f] (f rt))

(defn- rt []
  (get-in @(fw/fw-atom) [:platform :world-effects]))

(defn- call [k & args]
  (when-let [f (get (rt) k)]
    (apply f args)))

(defn spawn-lightning!* [world-id x y z]
  (call :spawn-lightning! world-id x y z))

(defn create-explosion!* [world-id x y z radius fire?]
  (call :create-explosion! world-id x y z radius fire?))

(defn spawn-projectile!* [world-id projectile-spec]
  (call :spawn-projectile! world-id projectile-spec))

(defn find-entities-in-radius* [world-id x y z radius]
  (call :find-entities-in-radius world-id x y z radius))

(defn find-entities-in-aabb* [world-id min-x min-y min-z max-x max-y max-z]
  (call :find-entities-in-aabb world-id min-x min-y min-z max-x max-y max-z))

(defn find-blocks-in-radius* [world-id x y z radius block-predicate]
  (call :find-blocks-in-radius world-id x y z radius block-predicate))

(defn play-sound!* [world-id x y z sound-id source volume pitch]
  (call :play-sound! world-id x y z sound-id source volume pitch))
