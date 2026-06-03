(ns cn.li.ac.content.ability.electromaster.thunder-clap
  "ThunderClap - channeled AOE lightning strike.

  Pattern: :charge-window (40..60 ticks)
  Start overload: lerp(390,252)
  Tick CP: lerp(18,25) while ticks <= 40
  Damage: lerp(36,72,exp) * lerp(1.0,1.2,extra-ratio)
  AOE radius: lerp(15,30,exp) with distance falloff
  Cooldown: ticks * lerp(10,6,exp)
  Exp: 0.003 per use"
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.damage :as damage-op]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.world :as world-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
                        [cn.li.mcmod.platform.raycast :as raycast]))

(def ^:private thunder-clap-skill-id :thunder-clap)

(defn- cfg-double [field-id]
  (skill-config/tunable-double thunder-clap-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int thunder-clap-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double thunder-clap-skill-id field-id exp))

(defn- min-ticks [] (cfg-int :charge.min-ticks))
(defn- max-ticks [] (cfg-int :charge.max-ticks))
(defn- targeting-range [] (cfg-double :targeting.range))

(defn- charge-window-span []
  (max 1 (- (max-ticks) (min-ticks))))

(defn- compute-overcharge-ratio
  [ticks]
  (bal/clamp01 (/ (- (double (or ticks 0)) (double (min-ticks)))
                   (double (charge-window-span)))))

(defn- resolve-fallback-target
  [player-id]
  (let [eye (geom/eye-pos player-id)
        look (when (raycast/available?)
               (raycast/get-player-look-vector* player-id))
        look* (or look {:x 0.0 :y 0.0 :z 1.0})
        range (targeting-range)]
    {:x (+ (double (:x eye)) (* (double (:x look*)) range))
     :y (+ (double (:y eye)) (* (double (:y look*)) range))
     :z (+ (double (:z eye)) (* (double (:z look*)) range))}))

(defn- block-impact-point
  [hit]
  {:x (double (or (:hit-x hit) (:x hit) 0.0))
   :y (double (or (:hit-y hit) (:y hit) 0.0))
   :z (double (or (:hit-z hit) (:z hit) 0.0))})

(defn- entity-impact-point
  [hit]
  {:x (double (or (:x hit) (:hit-x hit) 0.0))
   :y (+ (double (or (:y hit) (:hit-y hit) 0.0))
         (double (or (:eye-height hit) 0.0)))
   :z (double (or (:z hit) (:hit-z hit) 0.0))})

(defn- hit-kind
  [hit]
  (let [kind (:hit-type hit)]
    (cond
      (= kind :entity) :entity
      (= kind :block) :block
      :else :miss)))

(defn- resolve-raycast-target
  [player-id]
  (let [range (targeting-range)
        world-id (geom/world-id-of player-id)
        eye (geom/eye-pos player-id)
        look (when (raycast/available?)
               (raycast/get-player-look-vector* player-id))
        hit (when (and (raycast/available?) look)
              (raycast/raycast-combined*
                                        world-id
                                        (:x eye) (:y eye) (:z eye)
                                        (double (or (:x look) 0.0))
                                        (double (or (:y look) 0.0))
                                        (double (or (:z look) 1.0))
                                        (double range)))]
    (case (if hit (hit-kind hit) :miss)
      :entity (entity-impact-point hit)
      :block (block-impact-point hit)
      (resolve-fallback-target player-id))))

(defn- current-target
  [ctx-id player-id]
  (or (get-in (ctx/get-context ctx-id) [:skill-state :hit-pos])
      (resolve-fallback-target player-id)))

(defn- update-skill-state-root!
  [ctx-id f & args]
  (apply ctx-skill/update-skill-state-root! ctx-id f args))

(defn- refresh-hit-pos!
  [{:keys [ctx-id player-id]}]
  (update-skill-state-root! ctx-id assoc :hit-pos (resolve-raycast-target player-id))
  nil)

(defn- mark-performed!
  [ctx-id performed? & {:as extra-state}]
  (update-skill-state-root! ctx-id merge
                            (merge {:performed? (boolean performed?)} extra-state))
  (boolean performed?))

(defn- end-payload
  [{:keys [ctx-id player-id hold-ticks]}]
  (let [ticks (long (or hold-ticks 0))]
    {:performed?   (boolean (get-in (ctx/get-context ctx-id) [:skill-state :performed?]))
     :charge-ticks ticks
     :ticks        ticks
     :charge-ratio (compute-overcharge-ratio ticks)
     :target       (current-target ctx-id player-id)}))

(defskill thunder-clap
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
  :input-policy    {:settle-perform-on-key-up?
                    (fn [{:keys [ctx-id]}]
                      (boolean (get-in (ctx/get-context ctx-id) [:skill-state :performed?])))}
  :cooldown        {:mode :manual}
  :cost            {:down {:overload (fn [{:keys [exp]}]
                                      (cfg-lerp :cost.down.overload (double (or exp 0.0))))}
                    :tick {:cp (fn [{:keys [hold-ticks exp]}]
                                 (if (<= (long (or hold-ticks 0)) (min-ticks))
                                   (cfg-lerp :cost.tick.cp (bal/clamp01 (double (or exp 0.0))))
                                   0.0))}}
  :fx              {:start  {:topic   :thunder-clap/fx-start
                             :payload (fn [{:keys [ctx-id player-id]}]
                                        {:charge-ticks 0
                                         :ticks        0
                                         :charge-ratio 0.0
                                         :target       (current-target ctx-id player-id)})}
                    :update {:topic   :thunder-clap/fx-update
                             :payload (fn [{:keys [hold-ticks ctx-id player-id]}]
                                        (let [ticks (long (or hold-ticks 0))]
                                          {:charge-ticks ticks
                                           :ticks        ticks
                                           :charge-ratio (compute-overcharge-ratio ticks)
                                           :target       (current-target ctx-id player-id)}))}
                    :perform {:topic   :thunder-clap/fx-perform
                              :payload (fn [{:keys [hold-ticks ctx-id player-id]}]
                                         (let [ticks (long (or hold-ticks 0))]
                                           {:performed?   true
                                            :charge-ticks ticks
                                            :ticks        ticks
                                            :charge-ratio (compute-overcharge-ratio ticks)
                                            :target       (current-target ctx-id player-id)}))}
                    :end    {:topic   :thunder-clap/fx-end
                             :payload end-payload}}
  :actions
  {:down!      refresh-hit-pos!
   :tick!      refresh-hit-pos!
   :up!        (fn [{:keys [player-id ctx-id hold-ticks exp]}]
                 (let [ticks (long (or hold-ticks 0))]
                   (if (< ticks (min-ticks))
                     (mark-performed! ctx-id false :final-target (current-target ctx-id player-id))
                     (let [hit-pos  (current-target ctx-id player-id)
                           world-id (geom/world-id-of player-id)
                           exp*     (bal/clamp01 (double (or exp 0.0)))
                           mult     (cfg-lerp :combat.overcharge-multiplier
                                              (compute-overcharge-ratio ticks))
                           dmg      (* (cfg-lerp :combat.damage exp*) mult)
                           radius   (cfg-lerp :combat.aoe-radius exp*)
                           cooldown (max 1 (int (* (double ticks)
                                                    (cfg-lerp :cooldown.ticks-per-hold exp*))))]
                       (let [evt {:player-id player-id :ctx-id ctx-id :world-id world-id
                                  :hit-pos   hit-pos   :exp    exp*}]
                         (world-op/execute-spawn-lightning! evt {:at :hit-pos})
                         (damage-op/execute-damage-aoe! evt {:center      :hit-pos
                                                              :radius      radius
                                                              :amount      dmg
                                                              :damage-type :lightning}))
                       (skill-effects/set-main-cooldown! player-id :thunder-clap cooldown)
                       (skill-effects/add-skill-exp! player-id :thunder-clap
                                                     (cfg-double :progression.exp-use))
                       (mark-performed! ctx-id true :final-target hit-pos)))))
   :cost-fail! (fn [{:keys [ctx-id cost-stage]}]
                 (mark-performed! ctx-id false)
                 (when (= cost-stage :tick)
                   (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end
                                            (merge {:mode :end :ctx-id ctx-id}
                                                   (end-payload {:ctx-id ctx-id}))))
                 (ctx/terminate-context! ctx-id nil))
   :abort!     (fn [{:keys [ctx-id]}]
                 (mark-performed! ctx-id false))}
  :prerequisites [{:skill-id :thunder-bolt :min-exp 1.0}])

