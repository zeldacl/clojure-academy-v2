(ns cn.li.ac.content.ability.vecmanip.directed-shock
  "DirectedShock skill aligned to original AcademyCraft behavior.

  Key alignment points:
  - Charge window: valid release in (6, 50) ticks, auto-abort at 200 ticks
  - Resource use on perform only: CP lerp(50,100), overload lerp(18,12)
  - 3-block living ray trace, damage lerp(7,15), cooldown lerp(60,20)
  - Local hand prepare/punch animation and directed-shock sound on hit only
  - Hit impulse always applied; overwrite knockback at 25%+ exp matches original
  - EXP gain: 0.0035 on hit, 0.001 on miss

  No Minecraft imports."
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.content.ability.common :as ability-common]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private MIN-TICKS 6)
(def ^:private MAX-ACCEPTED-TICKS 50)
(def ^:private MAX-TOLERANT-TICKS 200)
(def ^:private RAYCAST-DISTANCE 3.0)

(defn- lerp [a b t]
  (ability-common/lerp a b t))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- v- [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn- v* [v scalar]
  {:x (* (double (:x v)) (double scalar))
   :y (* (double (:y v)) (double scalar))
   :z (* (double (:z v)) (double scalar))})

(defn- vlen [v]
  (Math/sqrt (+ (* (:x v) (:x v))
                (* (:y v) (:y v))
                (* (:z v) (:z v)))))

(defn- normalize [v]
  (let [len (max 1.0e-6 (vlen v))]
    (v* v (/ 1.0 len))))

(defn- get-skill-exp [player-id]
  (ability-common/get-skill-exp player-id :directed-shock))

(defn- player-pos [player-id]
  (get (ps/get-player-state player-id)
       :position
       {:world-id "minecraft:overworld"
        :x 0.0
        :y 64.0
        :z 0.0}))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- eye-pos [pos]
  {:x (double (:x pos))
   :y (+ (double (:y pos)) 1.62)
   :z (double (:z pos))})

(defn- target-pos [raycast-result]
  {:x (double (or (:x raycast-result) 0.0))
   :y (double (or (:y raycast-result) 0.0))
   :z (double (or (:z raycast-result) 0.0))
   :eye-height (double (or (:eye-height raycast-result) 1.62))})

(defn- damage-value [exp]
  (lerp 7.0 15.0 (clamp01 exp)))

(defn- cp-cost [exp]
  (lerp 50.0 100.0 (clamp01 exp)))

(defn- overload-cost [exp]
  (lerp 18.0 12.0 (clamp01 exp)))

(defn- cooldown-ticks [exp]
  (int (lerp 60.0 20.0 (clamp01 exp))))

(defn- add-exp! [player-id amount]
  (ability-common/add-skill-exp! player-id :directed-shock (double amount) 1.0))

(defn directed-shock-cost-up-cp
  [{:keys [player-id]}]
  (cp-cost (clamp01 (get-skill-exp player-id))))

(defn directed-shock-cost-up-overload
  [{:keys [player-id]}]
  (overload-cost (clamp01 (get-skill-exp player-id))))

(defn- apply-cooldown! [player-id exp]
  (ability-common/set-main-cooldown! player-id :directed-shock (cooldown-ticks exp)))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :directed-shock/fx-start {:mode :start}))

(defn- send-fx-perform! [ctx-id payload]
  (ctx/ctx-send-to-client! ctx-id :directed-shock/fx-perform (assoc payload :mode :perform)))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :directed-shock/fx-end {:mode :end
                                                          :performed? (boolean performed?)}))

(defn- hit-impulse [caster-pos hit-pos]
  (-> (v- hit-pos caster-pos)
      normalize
      (v* 0.24)))

(defn- knockback-velocity [caster-pos hit-pos]
  (let [player-head (eye-pos caster-pos)
        target-head {:x (:x hit-pos)
                     :y (+ (:y hit-pos) (:eye-height hit-pos))
                     :z (:z hit-pos)}
        delta0 (normalize (v- player-head target-head))
        delta1 (normalize {:x (:x delta0)
                           :y (- (:y delta0) 0.6)
                           :z (:z delta0)})]
    {:x (* (:x delta1) -0.7)
     :y (* (:y delta1) -0.7)
     :z (* (:z delta1) -0.7)}))

(defn directed-shock-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0
                          :performed? false})
    (send-fx-start! ctx-id)
    (log/debug "DirectedShock charge started")
    (catch Exception e
      (log/warn "DirectedShock key-down failed:" (ex-message e)))))

(defn directed-shock-on-key-tick
  "Update charge progress."
  [{:keys [ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            next-charge (inc charge-ticks)]
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-charge)
        (when (>= next-charge MAX-TOLERANT-TICKS)
          (send-fx-end! ctx-id false)
          (ctx/terminate-context! ctx-id nil)
          (log/debug "DirectedShock max tolerant ticks reached"))))
    (catch Exception e
      (log/warn "DirectedShock key-tick failed:" (ex-message e)))))

(defn directed-shock-on-key-up
  "Perform the punch attack."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))]
        (if (and (> charge-ticks MIN-TICKS)
                 (< charge-ticks MAX-ACCEPTED-TICKS))
          (let [exp (clamp01 (get-skill-exp player-id))]
            (if-not cost-ok?
              (do
                (send-fx-end! ctx-id false)
                (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
                (log/debug "DirectedShock perform failed: insufficient resource"))
              (let [world-id (player-world-id player-id)
                    caster-pos (player-pos player-id)
                    trace (when raycast/*raycast*
                            (raycast/raycast-from-player raycast/*raycast*
                                                         player-id
                                                         RAYCAST-DISTANCE
                                                         true))]
                (apply-cooldown! player-id exp)
                (if-let [target-id (:entity-id trace)]
                  (let [hit-pos (target-pos trace)
                        damage (damage-value exp)
                        impulse (hit-impulse caster-pos hit-pos)
                        knockback (when (>= exp 0.25)
                                    (knockback-velocity caster-pos hit-pos))]
                    (when entity-damage/*entity-damage*
                      (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                          world-id
                                                          target-id
                                                          damage
                                                          :generic))
                    (when (and knockback entity-motion/*entity-motion*)
                      (entity-motion/set-velocity! entity-motion/*entity-motion*
                                                   world-id
                                                   target-id
                                                   (:x knockback)
                                                   (:y knockback)
                                                   (:z knockback)))
                    (when entity-motion/*entity-motion*
                      (entity-motion/add-velocity! entity-motion/*entity-motion*
                                                   world-id
                                                   target-id
                                                   (:x impulse)
                                                   (:y impulse)
                                                   (:z impulse)))
                    (send-fx-perform! ctx-id {:target-id target-id
                                              :world-id world-id
                                              :impulse impulse
                                              :knockback knockback})
                    (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] true)
                    (add-exp! player-id 0.0035)
                    (log/info "DirectedShock hit entity" target-id "damage" (int damage)))
                  (do
                    (send-fx-end! ctx-id false)
                    (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
                    (add-exp! player-id 0.001)
                    (log/debug "DirectedShock missed"))))))
          (do
            (send-fx-end! ctx-id false)
            (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
            (log/debug "DirectedShock invalid charge" charge-ticks)))))
    (catch Exception e
      (log/warn "DirectedShock key-up failed:" (ex-message e)))))

(defn directed-shock-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (send-fx-end! ctx-id false)
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "DirectedShock aborted")
    (catch Exception e
      (log/warn "DirectedShock key-abort failed:" (ex-message e)))))

(defskill! directed-shock
  :id :directed-shock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.directed_shock"
  :description-key "ability.skill.vecmanip.directed_shock.desc"
  :icon "textures/abilities/vecmanip/skills/dir_shock.png"
  :ui-position [16 45]
  :level 1
  :controllable? false
  :ctrl-id :directed-shock
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 60
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:up {:cp directed-shock-cost-up-cp
              :overload directed-shock-cost-up-overload}}
  :actions {:down! directed-shock-on-key-down
            :tick! directed-shock-on-key-tick
            :up! directed-shock-on-key-up
            :abort! directed-shock-on-key-abort})
