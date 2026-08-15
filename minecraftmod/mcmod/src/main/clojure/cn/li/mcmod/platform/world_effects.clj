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

(defn execute-ray-barrage!
  "Execute a bounded ray-barrage plan supplied by Combat Core.

   The operation stays opaque to the neutral engine: each loader implements
   the directional ray test and special-target policy behind this port."
  [world-id owner plan]
  (call :execute-ray-barrage! world-id owner plan))

(defn execute-directed-blastwave!
  "Execute the bounded block/entity shockwave behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-directed-blastwave! world-id owner plan))

(defn execute-groundshock!
  "Execute the bounded ground propagation/launch plan behind a Host Port."
  [world-id owner plan]
  (call :execute-groundshock! world-id owner plan))

(defn execute-thunder-clap!
  "Execute the charged lightning/area-damage plan behind a Host Port."
  [world-id owner plan]
  (call :execute-thunder-clap! world-id owner plan))

(defn execute-blood-retrograde!
  "Execute the close-range blood damage/effect plan behind a Host Port."
  [world-id owner plan]
  (call :execute-blood-retrograde! world-id owner plan))

(defn execute-electron-missile!
  "Execute the bounded Electron Missile channel behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-electron-missile! world-id owner plan))

(defn execute-scatter-bomb!
  "Execute the bounded Scatter Bomb release behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-scatter-bomb! world-id owner plan))

(defn execute-plasma-cannon!
  "Execute the charged Plasma Cannon impact behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-plasma-cannon! world-id owner plan))

(defn execute-mine-ray!
  "Execute one bounded mining-ray tick behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-mine-ray! world-id owner plan))

(defn execute-meltdowner!
  "Execute the charged Meltdowner beam behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-meltdowner! world-id owner plan))

(defn execute-jet-engine!
  "Execute the bounded Jet Engine dash/segment plan behind a Host Port."
  [world-id owner plan]
  (call :execute-jet-engine! world-id owner plan))

(defn execute-electron-missile!
  "Execute the bounded Electron Missile volley behind a neutral Host Port."
  [world-id owner plan]
  (call :execute-electron-missile! world-id owner plan))

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
