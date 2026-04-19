(ns cn.li.ac.content.ability.meltdowner.electron-missile
  "ElectronMissile skill - launches homing energy balls at nearby enemies.

  Pattern: :hold-channel
  Max active balls: lerp(2, 5, exp)  (integer)
  Fire interval: lerp(20, 10, exp) ticks
  Damage per ball: lerp(4, 9, exp)
  Seek range: 20 blocks
  Tick cost: CP lerp(6, 4, exp)
  Down cost: overload lerp(30, 15, exp)
  Cooldown: 10 ticks
  Exp: +0.002 per entity hit

  Implementation: uses fake-ball tracking in context state rather than actual
  spawned entities, since entity spawning protocol is deferred.
  Balls are fired (hit-checked) immediately on fire-tick instead of traveling.

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :electron-missile :exp]
                  0.0)))

(defn- find-nearest-entity [player-id world-id seek-range]
  (when world-effects/*world-effects*
    (let [eye (geom/eye-pos player-id)
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

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn electron-missile-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state
                         {:fire-ticker 0 :active? true})))

(defn electron-missile-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (try
    (let [ctx-data (ctx/get-context ctx-id)
          state    (get ctx-data :skill-state {})
          ticker   (long (or (:fire-ticker state) 0))
          exp      (skill-exp player-id)
          max-balls (int (bal/lerp 2.0 5.0 exp))
          fire-interval (int (bal/lerp 20.0 10.0 exp))
          new-ticker (inc ticker)]
      ;; Anti-AFK
      (when (>= (long hold-ticks) 600)
        (log/debug "ElectronMissile: max hold reached")
        (ctx/terminate-context! ctx-id nil))
      ;; Fire a ball on interval
      (when (zero? (mod new-ticker fire-interval))
        (try
          (let [world-id (geom/world-id-of player-id)
                target   (find-nearest-entity player-id world-id 20.0)]
            (when target
              (let [damage (bal/lerp 4.0 9.0 exp)]
                (when entity-damage/*entity-damage*
                  (md-damage/mark-target! player-id (:uuid target))
                  (entity-damage/apply-direct-damage!
                    entity-damage/*entity-damage*
                    world-id (:uuid target) damage :magic))
                (skill-effects/add-skill-exp! player-id :electron-missile 0.002)
                (ctx/ctx-send-to-client! ctx-id :electron-missile/fx-fire
                                         {:target-x (:x target)
                                          :target-y (:y target)
                                          :target-z (:z target)}))))
          (catch Exception e
            (log/warn "ElectronMissile fire failed:" (ex-message e)))))
      (ctx/update-context! ctx-id assoc-in [:skill-state :fire-ticker] new-ticker))
    (catch Exception e
      (log/warn "ElectronMissile tick! failed:" (ex-message e)))))

(defn electron-missile-up!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state {:fire-ticker 0 :active? false}))

(defn electron-missile-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state {:fire-ticker 0 :active? false}))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! electron-missile
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
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                                      (bal/lerp 30.0 15.0 (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
                                (bal/lerp 6.0 4.0 (skill-exp player-id)))}}
  :cooldown-ticks 10
  :actions        {:down!  electron-missile-down!
                   :tick!  electron-missile-tick!
                   :up!    electron-missile-up!
                   :abort! electron-missile-abort!}
  :fx             {:start  {:topic :electron-missile/fx-start :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :jet-engine :min-exp 0.3}])
