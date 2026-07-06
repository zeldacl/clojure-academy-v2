(ns cn.li.ac.content.ability.meltdowner.electron-missile
  "ElectronMissile skill - hold to accumulate and fire electron balls.

  Pattern: :hold-channel
  Spawn interval: 10 ticks
  Fire interval: 8 ticks
  Max stored balls: 5
  Damage per ball: lerp(10, 18, exp)
  Seek range: lerp(5, 13, exp) blocks
  Tick CP cost: lerp(12, 5, exp)
  Down overload floor: 200
  Per-attack extra: CP lerp(60, 25, exp) + overload lerp(9, 4, exp)
  Cooldown: lerp(700, 400, exp) ticks (manual)
  Max hold: lerp(80, 200, exp) ticks
  Exp: +0.001 per entity hit

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
                        [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def-skill-config-ops :electron-missile)
(def ^:private mdball-entity-id "my_mod:entity_md_ball")
(def ^:private electron-missile-skill-id :electron-missile)

(defn- missile-filter-self
  "Remove the shooter from candidate list."
  [player-id e]
  (not= (str (:uuid e)) (str player-id)))

(defn- missile-dist-sq-from-eye
  "Squared distance from eye position to entity, for nearest-first sort."
  [eye e]
  (let [dx (- (double (:x e)) (double (:x eye)))
        dy (- (double (:y e)) (double (:y eye)))
        dz (- (double (:z e)) (double (:z eye)))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn- find-nearest-entity [player-id world-id exp]
  (when (world-effects/available?)
    (let [seek-range (cfg-lerp :targeting.seek-range exp)
          eye (geom/eye-pos player-id)
          candidates (world-effects/find-entities-in-radius*
                       world-id
                       (double (:x eye))
                       (double (:y eye))
                       (double (:z eye))
                       (double seek-range))]
      (->> candidates
           (filter (partial missile-filter-self player-id))
           (filter (fn [e] (:living? e false)))
           (sort-by (partial missile-dist-sq-from-eye eye))
           first))))

(defn- current-overload [player-id]
  (double (or (get-in (skill-effects/get-player-state player-id)
                      [:resource-data :cur-overload])
              0.0)))

(defn- try-pay-attack-cost! [player-id exp]
  (let [{:keys [success?]} (skill-effects/perform-resource!
                             player-id
                             (cfg-lerp :cost.attack.overload exp)
                             (cfg-lerp :cost.attack.cp exp))]
    (boolean success?)))

(defn- send-start-fx! [ctx-id]
  (fx/send-local-and-nearby! ctx-id {:topic :electron-missile/fx-start} nil {}))

(defn- send-update-fx! [ctx-id ticks active-balls]
  (fx/send-local-and-nearby! ctx-id {:topic :electron-missile/fx-update} nil
                               {:ticks ticks
                                :balls active-balls}))

(defn- send-fire-fx! [ctx-id start-pos target]
  (fx/send-local-and-nearby! ctx-id {:topic :electron-missile/fx-fire} nil
                               {:target-x (:x target)
                                :target-y (:y target)
                                :target-z (:z target)
                                :start start-pos
                                :end {:x (:x target)
                                      :y (+ (double (:y target)) (double (or (:eye-height target) 0.0)))
                                      :z (:z target)}}))

(defn- send-end-fx! [ctx-id]
  (fx/send-local-and-nearby! ctx-id {:topic :electron-missile/fx-end} nil {}))

(defn electron-missile-down!
  [ctx-id player-id _skill-id _exp cost-ok? _hold-ticks _cost-stage _player-ref]
  (when cost-ok?
    (let [overload-floor (max (cfg-double :cost.down.overload)
                              (current-overload player-id))]
      (ctx-skill/replace-skill-state! ctx-id
                             {:ticks 0
                              :active-balls 0
                              :active? true
                              :overload-floor overload-floor})
      (send-start-fx! ctx-id))))

(defn electron-missile-tick!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage player-ref]
  (try
    (let [ctx-data (ctx-skill/get-context ctx-id)
          state (get ctx-data :skill-state {})
          ticks (long (or (:ticks state) 0))
          active-balls (long (or (:active-balls state) 0))
          overload-floor (double (or (:overload-floor state) (cfg-double :cost.down.overload)))
          max-hold (cfg-lerp-int :charge.max-hold-ticks exp)
          max-balls (cfg-int :projectile.max-hold-balls)
          spawn-interval (cfg-int :timing.spawn-interval-ticks)
          fire-interval (cfg-int :timing.fire-interval-ticks)
          world-id (geom/world-id-of player-id)]
      (skill-effects/enforce-overload-floor! player-id overload-floor)
      (if (>= ticks max-hold)
        (do
          (log/debug "ElectronMissile: max hold reached" ticks "/" max-hold)
          (send-end-fx! ctx-id)
          (ctx/terminate-context! ctx-id nil))
        (let [balls-after-spawn (if (and (zero? (mod ticks spawn-interval))
                                         (< active-balls max-balls))
                                  (do
                                    (when player-ref
                                      (entity/player-spawn-entity-by-id! player-ref mdball-entity-id 0.0))
                                    (inc active-balls))
                                  active-balls)
              should-fire? (and (pos? ticks)
                                (pos? balls-after-spawn)
                                (zero? (mod ticks fire-interval)))
              balls-after-fire (if-not should-fire?
                                 balls-after-spawn
                                 (let [target (find-nearest-entity player-id world-id exp)]
                                   (if (and target
                                            (try-pay-attack-cost! player-id exp))
                                     (do
                                       (when (and (entity-damage/available?) (:uuid target))
                                         (entity-damage/apply-direct-damage!*
                                           world-id
                                           (:uuid target)
                                           (cfg-lerp :combat.damage exp)
                                           :magic)
                                          (md-damage/mark-target! player-id (:uuid target)
                                                                  {:ctx-id ctx-id
                                                                   :target-pos {:x (:x target)
                                                                                :y (:y target)
                                                                                :z (:z target)}})
                                         (skill-effects/add-skill-exp! player-id
                                                                       electron-missile-skill-id
                                                                       (cfg-double :progression.exp-hit)))
                                       (send-fire-fx! ctx-id (geom/eye-pos player-id) target)
                                       (dec balls-after-spawn))
                                     balls-after-spawn)))]
          (send-update-fx! ctx-id ticks balls-after-fire)
          (ctx-skill/replace-skill-state! ctx-id
                                 {:ticks (inc ticks)
                                  :active-balls balls-after-fire
                                  :active? true
                                  :overload-floor overload-floor}))))
    (catch Exception e
      (log/warn "ElectronMissile tick! failed:" (ex-message e)))))

(defn electron-missile-up!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [cd (cfg-lerp-int :cooldown.ticks exp)]
    (skill-effects/set-main-cooldown! player-id electron-missile-skill-id cd)
    (send-end-fx! ctx-id)
    (ctx-skill/replace-skill-state! ctx-id
                 {:ticks 0 :active-balls 0 :active? false})))

(defn electron-missile-abort!
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (send-end-fx! ctx-id)
  (ctx-skill/replace-skill-state! ctx-id
                         {:ticks 0 :active-balls 0 :active? false}))

(defskill electron-missile
  :id             :electron-missile
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.electron_missile"
  :description-key "ability.skill.meltdowner.electron_missile.desc"
  :icon           "textures/abilities/meltdowner/skills/electron_missile.png"
  :ui-position    [210 35]
  :level          5
  :controllable?  true
  :ctrl-id        :electron-missile
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :hold-channel
  :cost           {:down {:overload (fn [_] (cfg-double :cost.down.overload))}
                   :tick {:cp (fn [{:keys [player-id]}]
                                (cfg-lerp :cost.tick.cp (skill-exp player-id)))}}
  :cooldown       {:mode :manual}
  :cooldown-ticks (fn [{:keys [exp]}]
                    (skill-config/lerp-int electron-missile-skill-id
                                           :cooldown.ticks
                                           (double (or exp 0.0))))
  ;; matching original: clampi(700, 400, exp) �?cooldown �?[400, 700] ticks
  :actions        {:down!  electron-missile-down!
                   :tick!  electron-missile-tick!
                   :up!    electron-missile-up!
                   :abort! electron-missile-abort!}
  :fx             {:start  {:topic :electron-missile/fx-start :payload (fn [_] {})}
                   :update {:topic :electron-missile/fx-update :payload (fn [_] {})}
                   :end    {:topic :electron-missile/fx-end :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :jet-engine :min-exp 0.3}])

