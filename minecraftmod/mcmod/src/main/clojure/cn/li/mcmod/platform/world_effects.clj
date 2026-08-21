(ns cn.li.mcmod.platform.world-effects
  (:require [cn.li.mcmod.framework :as fw]))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :world-effects])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :world-effects]))

(defn- call
  [k & args]
  (let [ops (current)
        f (get ops k)]
    (when-not f
      (throw (ex-info "Required world-effects operation is not installed"
                      {:operation k :installed (keys ops)})))
    (apply f args)))

(defn spawn-lightning!
  ([world-id x y z]
   (call :spawn-lightning! world-id x y z))
  ([world-id x y z visual-only?]
   (call :spawn-lightning! world-id x y z visual-only?)))

(defn create-explosion!
  ([world-id x y z radius fire?]
   (call :create-explosion! world-id x y z radius fire?))
  ([world-id x y z radius fire? opts]
   (call :create-explosion! world-id x y z radius fire? opts)))

(defn spawn-projectile!
  [world-id projectile-spec]
  (call :spawn-projectile! world-id projectile-spec))

(defn spawn-entity!
  "Spawn a neutral tracked entity through the platform adapter."
  [world-id owner entity-type position velocity life-ticks]
  (call :spawn-entity! world-id owner entity-type position velocity life-ticks))

(defn execute-directed-blastwave!
  "Execute the bounded block/entity shockwave behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-directed-blastwave! world-id owner plan))

(defn execute-groundshock!
  "Execute the bounded ground propagation/launch plan behind a Host Port."
  [world-id owner plan]
  (call :execute-groundshock! world-id owner plan))

(defn execute-shift-teleport!
  "Place/drop the caster's held item at a raycasted point and damage
   whatever intersects the caster->point line, behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-shift-teleport! world-id owner plan))

(defn execute-blood-retrograde!
  "Execute the close-range blood damage/effect plan behind a Host Port."
  [world-id owner plan]
  (call :execute-blood-retrograde! world-id owner plan))

(defn execute-mine-ray!
  "Execute one bounded mining-ray tick behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-mine-ray! world-id owner plan))

(defn execute-vec-accel!
  "Execute the charged Vector Acceleration movement behind a Host Port."
  [world-id owner plan]
  (call :execute-vec-accel! world-id owner plan))

(defn execute-flashing!
  "Execute the approved Flashing movement behind a Host Port."
  [world-id owner plan]
  (call :execute-flashing! world-id owner plan))

(defn execute-vec-deviation!
  "Execute one server-authoritative VecDeviation scan/deflection step."
  [world-id owner plan]
  (call :execute-vec-deviation! world-id owner plan))

(defn execute-knockback!
  "Apply a directional velocity impulse to a target entity behind a neutral
   Host Port (e.g. Directed Shock)."
  [world-id owner plan]
  (call :execute-knockback! world-id owner plan))

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

(defn trigger-behavior-hit!
  [world-id entity-uuid]
  (boolean (call :trigger-behavior-hit! world-id entity-uuid)))

(defn discard-entity-by-uuid!
  "Server-side discard of an entity by uuid (e.g. removing a spawned effect
  entity when its ability context ends)."
  [world-id entity-uuid]
  (boolean (call :discard-entity-by-uuid! world-id entity-uuid)))
