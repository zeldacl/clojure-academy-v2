(ns cn.li.ac.content.ability.vecmanip.directed-shock
  "DirectedShock - melee punch with charge window.

  Pattern: :charge-window (valid window 6-50 ticks, abort at 200)
  Cost on perform: CP lerp(50,100), overload lerp(18,12) by exp
  Cooldown: lerp(60,20) ticks by exp
  Exp: +0.0035 on hit / +0.001 on miss"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private MIN-TICKS 6)
(def ^:private MAX-ACCEPTED-TICKS 50)
(def ^:private MAX-TOLERANT-TICKS 200)
(def ^:private RAYCAST-DISTANCE 3.0)

(defn- v- [a b] {:x (- (double (:x a)) (double (:x b)))
                 :y (- (double (:y a)) (double (:y b)))
                 :z (- (double (:z a)) (double (:z b)))})
(defn- v* [v s] {:x (* (double (:x v)) (double s))
                 :y (* (double (:y v)) (double s))
                 :z (* (double (:z v)) (double s))})
(defn- vlen [v] (Math/sqrt (+ (* (:x v) (:x v)) (* (:y v) (:y v)) (* (:z v) (:z v)))))
(defn- normalize [v] (let [l (max 1.0e-6 (vlen v))] (v* v (/ 1.0 l))))

(defn- player-pos [player-id]
  (get (ps/get-player-state player-id)
       :position {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- eye-pos [pos]
  {:x (double (:x pos)) :y (+ (double (:y pos)) 1.62) :z (double (:z pos))})

(defn- hit-impulse [caster-pos hit-pos]
  (-> (v- hit-pos caster-pos) normalize (v* 0.24)))

(defn- knockback-velocity [caster-pos hit-pos]
  (let [player-head (eye-pos caster-pos)
        target-head {:x (:x hit-pos)
                     :y (+ (:y hit-pos) (double (or (:eye-height hit-pos) 1.62)))
                     :z (:z hit-pos)}
        d0 (normalize (v- player-head target-head))
        d1 (normalize {:x (:x d0) :y (- (:y d0) 0.6) :z (:z d0)})]
    {:x (* (:x d1) -0.7) :y (* (:y d1) -0.7) :z (* (:z d1) -0.7)}))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :directed-shock/fx-start {:mode :start}))
(defn- send-fx-perform! [ctx-id payload]
  (ctx/ctx-send-to-client! ctx-id :directed-shock/fx-perform (assoc payload :mode :perform)))
(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :directed-shock/fx-end {:mode :end :performed? (boolean performed?)}))

(defskill! directed-shock
  :id          :directed-shock
  :category-id :vecmanip
  :name-key    "ability.skill.vecmanip.directed_shock"
  :description-key "ability.skill.vecmanip.directed_shock.desc"
  :icon        "textures/abilities/vecmanip/skills/dir_shock.png"
  :ui-position [16 45]
  :level       1
  :controllable? false
  :ctrl-id     :directed-shock
  :pattern     :charge-window
  :cooldown    {:mode :manual}
  :cost        {:up {:cp       (fn [{:keys [exp]}] (bal/lerp 50.0 100.0 (bal/clamp01 exp)))
                     :overload (fn [{:keys [exp]}] (bal/lerp 18.0  12.0 (bal/clamp01 exp)))}}
  :actions
  {:down! (fn [{:keys [ctx-id]}]
            (ctx/update-context! ctx-id assoc :skill-state {:charge-ticks 0 :performed? false})
            (send-fx-start! ctx-id))
   :tick! (fn [{:keys [ctx-id]}]
            (when-let [ctx-data (ctx/get-context ctx-id)]
              (let [next-charge (inc (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0)))]
                (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-charge)
                (when (>= next-charge MAX-TOLERANT-TICKS)
                  (send-fx-end! ctx-id false)
                  (ctx/terminate-context! ctx-id nil)))))
   :up!   (fn [{:keys [player-id ctx-id exp cost-ok?]}]
            (when-let [ctx-data (ctx/get-context ctx-id)]
              (let [charge-ticks (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0))
                    exp*         (bal/clamp01 exp)]
                (if (and (> charge-ticks MIN-TICKS) (< charge-ticks MAX-ACCEPTED-TICKS))
                  (if-not cost-ok?
                    (do (send-fx-end! ctx-id false)
                        (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))
                    (let [world-id   (player-world-id player-id)
                          caster-pos (player-pos player-id)
                          trace      (when raycast/*raycast*
                                       (raycast/raycast-from-player raycast/*raycast*
                                                                    player-id
                                                                    RAYCAST-DISTANCE
                                                                    true))]
                      (skill-effects/set-main-cooldown!
                       player-id :directed-shock
                       (int (bal/lerp 60.0 20.0 exp*)))
                      (if-let [target-id (:entity-id trace)]
                        (let [hit-pos  {:x (double (or (:x trace) 0.0))
                                        :y (double (or (:y trace) 0.0))
                                        :z (double (or (:z trace) 0.0))
                                        :eye-height (double (or (:eye-height trace) 1.62))}
                              damage   (bal/lerp 7.0 15.0 exp*)
                              impulse  (hit-impulse caster-pos hit-pos)
                              knockback (when (>= exp* 0.25)
                                          (knockback-velocity caster-pos hit-pos))]
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
                          (send-fx-perform! ctx-id {:target-id target-id
                                                    :world-id world-id
                                                    :impulse impulse
                                                    :knockback knockback})
                          (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] true)
                          (skill-effects/add-skill-exp! player-id :directed-shock 0.0035)
                          (log/info "DirectedShock hit" target-id "dmg" (int (bal/lerp 7.0 15.0 exp*))))
                        (do (send-fx-end! ctx-id false)
                            (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
                            (skill-effects/add-skill-exp! player-id :directed-shock 0.001)))))
                  (do (send-fx-end! ctx-id false)
                      (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))))))
   :abort! (fn [{:keys [ctx-id]}]
             (send-fx-end! ctx-id false)
             (ctx/update-context! ctx-id dissoc :skill-state))})
