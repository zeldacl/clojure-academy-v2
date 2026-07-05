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
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.fx :as fx]
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
  (when (raycast/available?)
    (raycast/raycast-from-player*
                                 player-id
                                 (cfg-double :targeting.raycast-distance)
                                 true)))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/update-skill-state-root! ctx-id identity state-map))

(defn- clear-skill-state!
  [ctx-id]
  (ctx-skill/clear-skill-state! ctx-id))

(defn- terminate-with-end!
  [ctx-id performed?]
  (fx/send! ctx-id {:topic :directed-shock/fx-end :mode :end} nil {:performed? performed?})
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (set-skill-state-root! ctx-id (assoc (:skill-state ctx-data) :performed? performed?)))
  (clear-skill-state! ctx-id)
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
            (set-skill-state-root! ctx-id
                                   {:charge-ticks 0
                                    :performed? false
                                    :punched? false
                                    :punch-ticks 0})
            (fx/send! ctx-id {:topic :directed-shock/fx-start :mode :start}))
   :tick! (fn [{:keys [ctx-id]}]
            (when-let [ctx-data (ctx-skill/get-context ctx-id)]
              (if (true? (get-in ctx-data [:skill-state :punched?]))
                (let [next-punch (inc (long (or (get-in ctx-data [:skill-state :punch-ticks]) 0)))]
                  (set-skill-state-root! ctx-id (assoc (:skill-state ctx-data) :punch-ticks next-punch))
                  (when (> next-punch (cfg-int :charge.punch-anim-ticks))
                    (terminate-with-end! ctx-id true)))
                (let [next-charge (inc (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0)))]
                  (set-skill-state-root! ctx-id (assoc (:skill-state ctx-data) :charge-ticks next-charge))
                  (when (>= next-charge (cfg-int :charge.max-tolerant-ticks))
                    (terminate-with-end! ctx-id false))))))
   :up! (fn [{:keys [player-id ctx-id exp cost-ok?]}]
          (when-let [ctx-data (ctx-skill/get-context ctx-id)]
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
                        (when (entity-damage/available?)
                          (entity-damage/apply-direct-damage!* world-id target-id damage :generic))
                        (when (and knockback (entity-motion/available?))
                          (entity-motion/set-velocity!* world-id target-id
                           (:x knockback) (:y knockback) (:z knockback)))
                        (when (entity-motion/available?)
                          (entity-motion/add-velocity!* world-id target-id
                           (:x impulse) (:y impulse) (:z impulse)))
                        (fx/send! ctx-id {:topic :directed-shock/fx-perform :mode :perform} nil {:target-id target-id
                                           :world-id world-id
                                           :impulse impulse
                                           :knockback knockback})
                           (set-skill-state-root! ctx-id
                                   (merge (:skill-state ctx-data)
                                     {:performed? true
                                      :punched? true
                                      :punch-ticks 0}))
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

