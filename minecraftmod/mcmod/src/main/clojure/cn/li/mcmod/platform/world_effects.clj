(ns cn.li.mcmod.platform.world-effects
  "Protocol for world-level effects (lightning, explosions, entity/block queries).

  Platform installs via install-world-effects!. Content uses *-suffixed wrappers.
  Impl stored in Framework [:platform :world-effects] — no ThreadLocal dependency."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IWorldEffects
  "World-level effects and queries."
  (spawn-lightning! [this world-id x y z])
  (create-explosion! [this world-id x y z radius fire?])
  (spawn-projectile! [this world-id projectile-spec])
  (find-entities-in-radius [this world-id x y z radius])
  (find-entities-in-aabb [this world-id min-x min-y min-z max-x max-y max-z])
  (find-blocks-in-radius [this world-id x y z radius block-predicate])
  (play-sound! [this world-id x y z sound-id source volume pitch]))

(defn install-world-effects!
  [impl label]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom assoc-in [:platform :world-effects] impl))
  nil)

(defn available? []
  (boolean (get-in @(fw/fw-atom) [:platform :world-effects])))

(defn current []
  (get-in @(fw/fw-atom) [:platform :world-effects]))

(defn call-with-runtime [rt f] (f rt))

(defn- rt []
  (get-in @(fw/fw-atom) [:platform :world-effects]))

(defn spawn-lightning!* [world-id x y z]
  (when-let [r (rt)] (spawn-lightning! r world-id x y z)))

(defn create-explosion!* [world-id x y z radius fire?]
  (when-let [r (rt)] (create-explosion! r world-id x y z radius fire?)))

(defn spawn-projectile!* [world-id projectile-spec]
  (when-let [r (rt)] (spawn-projectile! r world-id projectile-spec)))

(defn find-entities-in-radius* [world-id x y z radius]
  (when-let [r (rt)] (find-entities-in-radius r world-id x y z radius)))

(defn find-entities-in-aabb* [world-id min-x min-y min-z max-x max-y max-z]
  (when-let [r (rt)] (find-entities-in-aabb r world-id min-x min-y min-z max-x max-y max-z)))

(defn find-blocks-in-radius* [world-id x y z radius block-predicate]
  (when-let [r (rt)] (find-blocks-in-radius r world-id x y z radius block-predicate)))

(defn play-sound!* [world-id x y z sound-id source volume pitch]
  (when-let [r (rt)] (play-sound! r world-id x y z sound-id source volume pitch)))
