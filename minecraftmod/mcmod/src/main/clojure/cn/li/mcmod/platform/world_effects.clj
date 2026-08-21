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

(defn execute-groundshock!
  "Execute the bounded ground propagation/launch plan behind a Host Port."
  [world-id owner plan]
  (call :execute-groundshock! world-id owner plan))

(defn execute-shift-teleport!
  "Place/drop the caster's held item at a raycasted point and damage
   whatever intersects the caster->point line, behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-shift-teleport! world-id owner plan))

(defn execute-flashing!
  "Execute the approved Flashing movement behind a Host Port."
  [world-id owner plan]
  (call :execute-flashing! world-id owner plan))

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

(defn configure-entity!
  "Neutral entity configuration relay.  The platform decides how a UUID is
   resolved and applies the requested velocity/tags; mcmod only forwards data."
  [world-id entity-uuid velocity add-tags projectile-damage]
  (boolean (call :configure-entity! world-id entity-uuid velocity add-tags projectile-damage)))

(defn teleport-entity!
  "Neutral relay for moving an entity to an absolute position."
  [world-id entity-uuid x y z]
  (boolean (call :teleport-entity! world-id entity-uuid
                 (double x) (double y) (double z))))

(defn add-entity-velocity!
  "Neutral relay for adding an entity velocity delta."
  [world-id entity-uuid x y z]
  (boolean (call :add-entity-velocity! world-id entity-uuid
                 (double x) (double y) (double z))))
