(ns cn.li.ac.content.ability.vecmanip.directed-shock
  "DirectedShock - melee punch with charge window.

  Pattern: :charge-window (valid window 6-50 ticks, abort at 200)
  Cost on perform: CP lerp(50,100), overload lerp(18,12) by exp
  Cooldown: lerp(60,20) ticks by exp (hit-only)
  Exp: +0.0035 on hit / +0.001 on miss"
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private directed-shock-skill-id :directed-shock)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double directed-shock-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int directed-shock-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double directed-shock-skill-id field-id (exp01 exp)))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int directed-shock-skill-id field-id (exp01 exp)))

(defn- entity-trace
  [player-id]
  (when raycast/*raycast*
    (raycast/raycast-from-player raycast/*raycast*
                                 player-id
                                 (cfg-double :targeting.raycast-distance)
                                 true)))

(defn- terminate-with-end!
  [ctx-id performed?]
  (fx/send-end! ctx-id :directed-shock/fx-end {:performed? performed?})
  (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] performed?)
  (ctx/update-context! ctx-id dissoc :skill-state)
  (ctx/terminate-context! ctx-id nil))

(defn- hit-impulse [caster-pos hit-pos]
  (-> (geom/v- hit-pos caster-pos)
      geom/vnorm
      (geom/v* (cfg-double :movement.hit-impulse))))

(defn- knockback-velocity [player-id hit-pos]
  (let [player-head (geom/eye-pos player-id)
        target-head {:x (:x hit-pos)
                     :y (+ (:y hit-pos)
                           (double (or (:eye-height hit-pos)
                                       (cfg-double :targeting.eye-height))))
                     :z (:z hit-pos)}
        d0 (geom/vnorm (geom/v- player-head target-head))
        d1 (geom/vnorm {:x (:x d0)
                        :y (- (:y d0) (cfg-double :movement.knockback-y-adjust))
                        :z (:z d0)})
        scale (cfg-double :movement.knockback-scale)]
    {:x (* (:x d1) scale)
     :y (* (:y d1) scale)
     :z (* (:z d1) scale)}))

(defskill directed-shock
  :id :directed-shock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.directed_shock"
  :description-key "ability.skill.vecmanip.directed_shock.desc"
  :icon "textures/abilities/vecmanip/skills/dir_shock.png"
  :ui-position [16 45]
  :level 1
  :controllable? false
  :ctrl-id :directed-shock
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:up {:cp (fn [{:keys [exp]}] (cfg-lerp :cost.up.cp exp))
              :overload (fn [{:keys [exp]}] (cfg-lerp :cost.up.overload exp))}}
  :actions
  {:down! (fn [{:keys [ctx-id]}]
            (ctx/update-context! ctx-id assoc :skill-state
                                 {:charge-ticks 0
                                  :performed? false
                                  :punched? false
                                  :punch-ticks 0})
            (fx/send-start! ctx-id :directed-shock/fx-start))
   :tick! (fn [{:keys [ctx-id]}]
            (when-let [ctx-data (ctx/get-context ctx-id)]
              (if (true? (get-in ctx-data [:skill-state :punched?]))
                (let [next-punch (inc (long (or (get-in ctx-data [:skill-state :punch-ticks]) 0)))]
                  (ctx/update-context! ctx-id assoc-in [:skill-state :punch-ticks] next-punch)
                  (when (> next-punch (cfg-int :charge.punch-anim-ticks))
                    (terminate-with-end! ctx-id true)))
                (let [next-charge (inc (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0)))]
                  (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-charge)
                  (when (>= next-charge (cfg-int :charge.max-tolerant-ticks))
                    (terminate-with-end! ctx-id false))))))
   :up! (fn [{:keys [player-id ctx-id exp cost-ok?]}]
          (when-let [ctx-data (ctx/get-context ctx-id)]
            (let [charge-ticks (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0))
                  exp* (exp01 exp)]
              (if (and (> charge-ticks (cfg-int :charge.min-ticks))
                       (< charge-ticks (cfg-int :charge.max-accepted-ticks)))
                (if-not cost-ok?
                  (terminate-with-end! ctx-id false)
                  (let [world-id (geom/world-id-of player-id)
                        eye (geom/eye-pos player-id)
                  trace (entity-trace player-id)]
                    (if-let [target-id (:entity-id trace)]
                      (let [hit-pos {:x (double (or (:x trace) 0.0))
                                     :y (double (or (:y trace) 0.0))
                                     :z (double (or (:z trace) 0.0))
                                     :eye-height (double (or (:eye-height trace)
                                                             (cfg-double :targeting.eye-height)))}
                            damage (cfg-lerp :combat.damage exp*)
                            impulse (hit-impulse eye hit-pos)
                            knockback (when (>= exp* (cfg-double :movement.knockback-exp-threshold))
                                        (knockback-velocity player-id hit-pos))]
                        (when entity-damage/*entity-damage*
                          (entity-damage/apply-direct-damage!
                           entity-damage/*entity-damage* world-id target-id damage :generic))
                        (when (and knockback entity-motion/*entity-motion*)
                          (entity-motion/set-velocity!
                           entity-motion/*entity-motion* world-id target-id
                           (:x knockback) (:y knockback) (:z knockback)))
                        (when entity-motion/*entity-motion*
                          (entity-motion/add-velocity!
                           entity-motion/*entity-motion* world-id target-id
                           (:x impulse) (:y impulse) (:z impulse)))
                        (fx/send-perform! ctx-id :directed-shock/fx-perform
                                          {:target-id target-id
                                           :world-id world-id
                                           :impulse impulse
                                           :knockback knockback})
                        (ctx/update-context! ctx-id update :skill-state merge
                                             {:performed? true
                                              :punched? true
                                              :punch-ticks 0})
                        (skill-effects/add-skill-exp! player-id :directed-shock (cfg-double :progression.exp-hit))
                        (skill-effects/set-main-cooldown!
                         player-id :directed-shock (cfg-lerp-int :cooldown.ticks exp*))
                        (log/info "DirectedShock hit" target-id "dmg" (int damage)))
                      (do
                        (skill-effects/add-skill-exp! player-id :directed-shock (cfg-double :progression.exp-miss))
                        (terminate-with-end! ctx-id false)))))
                (terminate-with-end! ctx-id false)))))
   :abort! (fn [{:keys [ctx-id]}]
             (terminate-with-end! ctx-id false))})

