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
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private mdball-entity-id "my_mod:entity_md_ball")
(def ^:private electron-missile-skill-id :electron-missile)

(defn- cfg-double [field-id]
  (skill-config/tunable-double electron-missile-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int electron-missile-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double electron-missile-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int electron-missile-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id electron-missile-skill-id))

(defn- find-nearest-entity [player-id world-id exp]
  (when world-effects/*world-effects*
    (let [seek-range (cfg-lerp :targeting.seek-range exp)
          eye (geom/eye-pos player-id)
          candidates (world-effects/find-entities-in-radius
                       world-effects/*world-effects*
                       world-id
                       (double (:x eye))
                       (double (:y eye))
                       (double (:z eye))
                       (double seek-range))]
      (->> candidates
           (filter (fn [e] (not= (str (:uuid e)) (str player-id))))
           (filter (fn [e] (:living? e false)))
           (sort-by (fn [e]
                      (let [dx (- (double (:x e)) (double (:x eye)))
                            dy (- (double (:y e)) (double (:y eye)))
                            dz (- (double (:z e)) (double (:z eye)))]
                        (+ (* dx dx) (* dy dy) (* dz dz)))))
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

(defn- send-fx-to-local-and-nearby! [ctx-id channel payload]
  (ctx/ctx-send-to-client! ctx-id channel payload)
  (ctx/ctx-send-to-except-local! ctx-id channel payload))

(defn- send-start-fx! [ctx-id]
  (send-fx-to-local-and-nearby! ctx-id :electron-missile/fx-start {}))

(defn- send-update-fx! [ctx-id ticks active-balls]
  (send-fx-to-local-and-nearby! ctx-id :electron-missile/fx-update
                                {:ticks ticks
                                 :balls active-balls}))

(defn- send-fire-fx! [ctx-id start-pos target]
  (send-fx-to-local-and-nearby! ctx-id :electron-missile/fx-fire
                                {:target-x (:x target)
                                 :target-y (:y target)
                                 :target-z (:z target)
                                 :start start-pos
                                 :end {:x (:x target)
                                       :y (+ (double (:y target)) (double (or (:eye-height target) 0.0)))
                                       :z (:z target)}}))

(defn- send-end-fx! [ctx-id]
  (send-fx-to-local-and-nearby! ctx-id :electron-missile/fx-end {}))

(defn electron-missile-down!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when cost-ok?
    (let [overload-floor (max (cfg-double :cost.down.overload)
                              (current-overload player-id))]
      (ctx/update-context! ctx-id assoc :skill-state
                           {:ticks 0
                            :active-balls 0
                            :active? true
                            :overload-floor overload-floor})
      (send-start-fx! ctx-id))))

(defn electron-missile-tick!
  [{:keys [player-id ctx-id player]}]
  (try
    (let [ctx-data (ctx/get-context ctx-id)
          state (get ctx-data :skill-state {})
          ticks (long (or (:ticks state) 0))
          active-balls (long (or (:active-balls state) 0))
          overload-floor (double (or (:overload-floor state) (cfg-double :cost.down.overload)))
          exp (skill-exp player-id)
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
                                    (when player
                                      (entity/player-spawn-entity-by-id! player mdball-entity-id 0.0))
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
                                       (when (and entity-damage/*entity-damage* (:uuid target))
                                         (entity-damage/apply-direct-damage!
                                           entity-damage/*entity-damage*
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
          (ctx/update-context! ctx-id assoc :skill-state
                               {:ticks (inc ticks)
                                :active-balls balls-after-fire
                                :active? true
                                :overload-floor overload-floor}))))
    (catch Exception e
      (log/warn "ElectronMissile tick! failed:" (ex-message e)))))

(defn electron-missile-up!
  [{:keys [player-id ctx-id]}]
  (let [exp (skill-exp player-id)
        cd (cfg-lerp-int :cooldown.ticks exp)]
    (skill-effects/set-main-cooldown! player-id electron-missile-skill-id cd)
    (send-end-fx! ctx-id)
    (ctx/update-context! ctx-id assoc :skill-state
                         {:ticks 0 :active-balls 0 :active? false})))

(defn electron-missile-abort!
  [{:keys [ctx-id]}]
  (send-end-fx! ctx-id)
  (ctx/update-context! ctx-id assoc :skill-state
                       {:ticks 0 :active-balls 0 :active? false}))

(defn init!
  "Explicit runtime installer for Meltdowner shared damage helper hooks."
  []
  (md-damage/init!)
  nil)

(defskill electron-missile
  :id             :electron-missile
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.electron_missile"
  :description-key "ability.skill.meltdowner.electron_missile.desc"
  :icon           "textures/abilities/meltdowner/skills/electron_missile.png"
  :ui-position    [60 240]
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
  :cooldown-ticks 1
  :actions        {:down!  electron-missile-down!
                   :tick!  electron-missile-tick!
                   :up!    electron-missile-up!
                   :abort! electron-missile-abort!}
  :fx             {:start  {:topic :electron-missile/fx-start :payload (fn [_] {})}
                   :update {:topic :electron-missile/fx-update :payload (fn [_] {})}
                   :end    {:topic :electron-missile/fx-end :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :jet-engine :min-exp 0.3}])
