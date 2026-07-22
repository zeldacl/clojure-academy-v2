(ns cn.li.ac.ability.effects.world
  (:require [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.framework :as fw]))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :world-effects])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :world-effects]))

(defn- call
  [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn spawn-lightning!
  [world-id x y z]
  (call :spawn-lightning! world-id x y z))

(defn create-explosion!
  [world-id x y z radius fire?]
  (call :create-explosion! world-id x y z radius fire?))

(defn spawn-projectile!
  [world-id projectile-spec]
  (call :spawn-projectile! world-id projectile-spec))

(defn find-entities-in-radius
  [world-id x y z radius]
  (call :find-entities-in-radius world-id x y z radius))

(defn find-entities-in-aabb
  [world-id min-x min-y min-z max-x max-y max-z]
  (call :find-entities-in-aabb world-id min-x min-y min-z max-x max-y max-z))

(defn find-blocks-in-radius
  [world-id x y z radius block-predicate]
  (call :find-blocks-in-radius world-id x y z radius block-predicate))

(defn play-sound!
  [world-id x y z sound-id source volume pitch]
  (call :play-sound! world-id x y z sound-id source volume pitch))

(defn execute-spawn-entity-from-player!
  [evt {:keys [entity-id speed]}]
  (when-let [player (:player evt)]
    (entity/player-spawn-entity-by-id! player
                                       (str entity-id)
                                       (double (or speed 0.0))))
  evt)

(defn execute-spawn-lightning!
  [evt {:keys [at]}]
  (when (available?)
    (let [{:keys [x y z]} (or (when (map? at) at) (get evt at))]
      (spawn-lightning!
        (:world-id evt)
        (double x) (double y) (double z))))
  evt)

(defn execute-create-explosion!
  [evt {:keys [at radius fire?]}]
  (when (available?)
    (let [{:keys [x y z]} (or (when (map? at) at) (get evt at))]
      (create-explosion!
        (:world-id evt)
        (double x) (double y) (double z)
        (double (or radius 4.0))
        (boolean fire?))))
  evt)

