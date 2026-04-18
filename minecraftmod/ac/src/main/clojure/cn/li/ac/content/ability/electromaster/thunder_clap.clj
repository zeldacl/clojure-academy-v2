(ns cn.li.ac.content.ability.electromaster.thunder-clap
  "ThunderClap - channeled AOE lightning strike.

  Pattern: :charge-window (40..60 ticks)
  Start overload: lerp(390,252)
  Tick CP: lerp(18,25) while ticks <= 40
  Damage: lerp(36,72,exp) * lerp(1.0,1.2,extra-ratio)
  AOE radius: lerp(15,30,exp) with distance falloff
  Cooldown: ticks * lerp(10,6,exp)
  Exp: 0.003 per use"
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.damage]
            [cn.li.ac.ability.server.effect.world]
            [cn.li.ac.ability.server.effect.state]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]))

(def ^:private min-ticks 40)
(def ^:private max-ticks 60)

(defskill! thunder-clap
  :id              :thunder-clap
  :category-id     :electromaster
  :name-key        "ability.skill.electromaster.thunder_clap"
  :description-key "ability.skill.electromaster.thunder_clap.desc"
  :icon            "textures/abilities/electromaster/skills/thunder_clap.png"
  :ui-position     [204 80]
  :level           1
  :controllable?   true
  :ctrl-id         :thunder-clap
  :pattern         :charge-window
  :cooldown        {:mode :manual}
  :cost            {:down {:overload (bal/by-exp 390.0 252.0)}
                    :tick {:cp (fn [{:keys [hold-ticks exp]}]
                                 (when (<= (long (or hold-ticks 0)) min-ticks)
                                   (bal/lerp 18.0 25.0 (bal/clamp01 (double (or exp 0.0))))))}}
  :on-down         [[:aim-raycast {:range 40}]
                    [:assoc-state {:k :hit-pos :v :hit}]]
  :on-tick         [[:aim-raycast {:range 40}]
                    [:assoc-state {:k :hit-pos :v :hit}]]
  :fx              {:start  {:topic   :thunder-clap/fx-start
                             :payload (fn [{:keys [ctx-id]}]
                                        {:ticks        0
                                         :charge-ratio 0.0
                                         :target       (get-in (ctx/get-context ctx-id)
                                                               [:skill-state :hit-pos])})}
                    :update {:topic   :thunder-clap/fx-update
                             :payload (fn [{:keys [hold-ticks ctx-id]}]
                                        {:ticks        (long (or hold-ticks 0))
                                         :charge-ratio (bal/clamp01 (/ (double (or hold-ticks 0))
                                                                        (double max-ticks)))
                                         :target       (get-in (ctx/get-context ctx-id)
                                                               [:skill-state :hit-pos])})}}
  :actions
  {:up!        (fn [{:keys [player-id ctx-id hold-ticks exp]}]
                 (let [ticks (long (or hold-ticks 0))]
                   (if (< ticks min-ticks)
                     (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? false})
                     (let [hit-pos  (or (get-in (ctx/get-context ctx-id) [:skill-state :hit-pos])
                                        {:x 0.0 :y 64.0 :z 0.0})
                           world-id (geom/world-id-of player-id)
                           exp*     (bal/clamp01 (double (or exp 0.0)))
                           mult     (bal/lerp 1.0 1.2 (/ (- (double ticks) 40.0) 60.0))
                           dmg      (* (bal/lerp 36.0 72.0 exp*) mult)
                           radius   (bal/lerp 15.0 30.0 exp*)
                           cooldown (max 1 (int (* (double ticks) (bal/lerp 10.0 6.0 exp*))))]
                       (effect/run-ops!
                        {:player-id player-id :ctx-id ctx-id :world-id world-id
                         :hit-pos   hit-pos   :exp    exp*}
                        [[:spawn-lightning {:at :hit-pos}]
                         [:damage-aoe {:center      :hit-pos
                                       :radius      radius
                                       :amount      dmg
                                       :damage-type :lightning}]])
                       (skill-effects/set-main-cooldown! player-id :thunder-clap cooldown)
                       (skill-effects/add-skill-exp! player-id :thunder-clap 0.003)
                       (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? true})))))
   :cost-fail! (fn [{:keys [ctx-id cost-stage]}]
                 (when (= cost-stage :tick)
                   (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? false}))
                 (ctx/terminate-context! ctx-id nil))
   :abort!     (fn [{:keys [ctx-id]}]
                 (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? false}))}
  :prerequisites [{:skill-id :thunder-bolt :min-exp 1.0}])
