(ns cn.li.ac.content.ability.meltdowner.electron-missile
  "ElectronMissile skill - launches homing energy balls at nearby enemies.

  Pattern: :hold-channel
  Fire interval: 8 ticks (constant, from upstream)
  Damage per ball: lerp(10, 18, exp)  [was wrong: lerp(4,9,exp)]
  Seek range: lerp(5, 13, exp) blocks
  Tick CP cost: lerp(12, 5, exp)
  Down overload: 200 (floor, constant)
  Per-attack extra: CP lerp(60, 25, exp) + overload lerp(9, 4, exp)
  Cooldown: lerp(700, 400, exp) ticks  (manual)
  Max hold: lerp(80, 200, exp) ticks
  Exp: +0.002 per entity hit
  Radiation: marks target if rad-intensify is learned (via damage-helper)

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

;; Ensure radiation damage handler is installed at load time
(md-damage/ensure-damage-handler!)

(def ^:private mdball-entity-id "my_mod:entity_md_ball")

(defn- estimate-travel-ticks
  [start-pos target]
  (let [dx (- (double (:x target 0.0)) (double (:x start-pos 0.0)))
        dy (- (double (:y target 0.0)) (double (:y start-pos 0.0)))
        dz (- (double (:z target 0.0)) (double (:z start-pos 0.0)))
        dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
        ticks (Math/ceil (/ dist 1.6))]
    (int (max 2 (min 20 ticks)))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :electron-missile :exp]
                  0.0)))

(defn- find-nearest-entity [player-id world-id exp]
  (when world-effects/*world-effects*
    (let [seek-range (bal/lerp 5.0 13.0 exp)
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

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn electron-missile-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state
                         {:fire-ticker 0 :active? true})))

(defn electron-missile-tick!
  [{:keys [player-id ctx-id hold-ticks player]}]
  (try
    (let [ctx-data   (ctx/get-context ctx-id)
          state      (get ctx-data :skill-state {})
          ticker     (long (or (:fire-ticker state) 0))
          exp        (skill-exp player-id)
          max-hold   (int (bal/lerp 80.0 200.0 exp))
          new-ticker (inc ticker)]
      ;; Anti-AFK: terminate after max-hold ticks (lerp 80→200 based on exp)
      (when (>= (long hold-ticks) max-hold)
        (log/debug "ElectronMissile: max hold reached" hold-ticks "/" max-hold)
        (ctx/terminate-context! ctx-id nil))
      ;; Fire a ball every 8 ticks (fixed, per upstream)
      (when (zero? (mod new-ticker 8))
        (try
          (let [world-id (geom/world-id-of player-id)
                target   (find-nearest-entity player-id world-id exp)]
            (when target
              (let [damage      (bal/lerp 10.0 18.0 exp)
                    target-uuid (:uuid target)]
                (when player
                  (entity/player-spawn-entity-by-id! player mdball-entity-id 0.0))
                (let [start-pos    (geom/eye-pos player-id)
                      travel-ticks (estimate-travel-ticks start-pos target)]
                  (delayed-projectiles/schedule-electron-missile-hit!
                    {:player-id   player-id
                     :ctx-id      ctx-id
                     :world-id    world-id
                     :target-uuid target-uuid
                     :target-pos  {:target-x (:x target)
                                   :target-y (:y target)
                                   :target-z (:z target)}
                     :damage      damage
                     :delay-ticks travel-ticks
                     ;; Damage-helper: mark target for RadiationIntensify amplification
                     :on-hit!     (fn [hit-uuid]
                                    (md-damage/mark-target! player-id hit-uuid)
                                    (skill-effects/add-skill-exp! player-id :electron-missile 0.002))})))))
          (catch Exception e
            (log/warn "ElectronMissile fire failed:" (ex-message e)))))
      (ctx/update-context! ctx-id assoc-in [:skill-state :fire-ticker] new-ticker))
    (catch Exception e
      (log/warn "ElectronMissile tick! failed:" (ex-message e)))))

(defn electron-missile-up!
  [{:keys [player-id ctx-id]}]
  (let [exp (skill-exp player-id)
        cd  (int (bal/lerp 700.0 400.0 exp))]
    (skill-effects/set-main-cooldown! player-id :electron-missile cd)
    (ctx/update-context! ctx-id assoc :skill-state {:fire-ticker 0 :active? false})))

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
  :cost           {:down {:overload (constantly 200.0)}
                   :tick {:cp (fn [{:keys [player-id]}]
                                (bal/lerp 12.0 5.0 (skill-exp player-id)))}}
  :cooldown       {:mode :manual}
  :cooldown-ticks 1
  :actions        {:down!  electron-missile-down!
                   :tick!  electron-missile-tick!
                   :up!    electron-missile-up!
                   :abort! electron-missile-abort!}
  :fx             {:start  {:topic :electron-missile/fx-start :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :jet-engine :min-exp 0.3}])
