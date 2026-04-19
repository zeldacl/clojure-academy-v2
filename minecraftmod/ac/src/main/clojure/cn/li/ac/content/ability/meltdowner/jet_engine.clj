(ns cn.li.ac.content.ability.meltdowner.jet-engine
  "JetEngine skill - propels player in look direction at high speed.

  Pattern: :release-cast (charge before launching; longer hold = more speed)
  Min hold: 10 ticks  |  Max hold: 80 ticks
  Speed: lerp(1.2, 2.8, exp) * charge multiplier (1.0 → 2.5)
  Damage on impact: lerp(6, 12, exp) against entities in path
  Collision radius: 1.5 blocks
  CP cost: lerp(200, 150, exp)
  Overload cost: lerp(60, 40, exp)
  Cooldown: lerp(60, 35, exp) ticks
  Exp: +0.002 per cast

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
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :jet-engine :exp]
                  0.0)))

(def ^:private min-hold  10)
(def ^:private max-hold  80)

(defn- charge-ratio [hold-ticks]
  (-> hold-ticks
      (- min-hold)
      (max 0)
      (/ (double (- max-hold min-hold)))
      (min 1.0)))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn jet-engine-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:charging? true :ticks 0})))

(defn jet-engine-tick!
  [{:keys [ctx-id hold-ticks]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :ticks] hold-ticks)
  ;; Cancel anti-AFK at max hold
  (when (>= (long hold-ticks) max-hold)
    (ctx/ctx-send-to-client! ctx-id :jet-engine/fx-charge-max {})))

(defn jet-engine-up!
  [{:keys [player-id ctx-id hold-ticks]}]
  (try
    (let [exp     (skill-exp player-id)
          charge  (charge-ratio (long hold-ticks))
          base-speed (bal/lerp 1.2 2.8 exp)
          speed   (* base-speed (+ 1.0 (* 1.5 charge)))
          world-id (geom/world-id-of player-id)
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when (and look-vec player-motion/*player-motion*)
        ;; Apply velocity in look direction
        (player-motion/set-velocity!
          player-motion/*player-motion*
          player-id
          (* speed (double (:x look-vec)))
          (* speed (double (:y look-vec)))
          (* speed (double (:z look-vec))))
        ;; Cancel fall damage for this jump
        (when teleportation/*teleportation*
          (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
        ;; Damage nearby entities along path
        (when entity-damage/*entity-damage*
          (let [eye    (geom/eye-pos player-id)
                damage (bal/lerp 6.0 12.0 exp)]
            (doseq [step [1 2 3 4]]
              (let [sx (+ (double (:x eye)) (* step (double (:x look-vec))))
                    sy (+ (double (:y eye)) (* step (double (:y look-vec))))
                    sz (+ (double (:z eye)) (* step (double (:z look-vec))))
                    near-entities (when world-effects/*world-effects*
                                  (world-effects/find-entities-in-radius
                                    world-effects/*world-effects*
                                    world-id sx sy sz 1.5))]
                (doseq [e near-entities]
                  (when (not= (str (:uuid e)) (str player-id))
                    (md-damage/mark-target! player-id (:uuid e))
                    (entity-damage/apply-direct-damage!
                      entity-damage/*entity-damage*
                      world-id (:uuid e) damage :magic)))))))
        (skill-effects/add-skill-exp! player-id :jet-engine 0.002)
        (ctx/ctx-send-to-client! ctx-id :jet-engine/fx-launch
                                 {:speed speed
                                  :dx (:x look-vec) :dy (:y look-vec) :dz (:z look-vec)})))
    (catch Exception e
      (log/warn "JetEngine up! failed:" (ex-message e)))))

(defn jet-engine-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state {:charging? false}))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! jet-engine
  :id             :jet-engine
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.jet_engine"
  :description-key "ability.skill.meltdowner.jet_engine.desc"
  :icon           "textures/abilities/meltdowner/skills/jet_engine.png"
  :ui-position    [120 240]
  :level          4
  :controllable?  true
  :ctrl-id        :jet-engine
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 200.0 150.0 (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 60.0 40.0 (skill-exp player-id)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  jet-engine-down!
                   :tick!  jet-engine-tick!
                   :up!    (fn [{:keys [player-id ctx-id hold-ticks] :as evt}]
                             (let [exp (skill-exp player-id)
                                   cd  (int (bal/lerp 60.0 35.0 exp))]
                               (jet-engine-up! evt)
                               (skill-effects/set-main-cooldown! player-id :jet-engine cd)))
                   :abort! jet-engine-abort!}
  :fx             {:start  {:topic :jet-engine/fx-start  :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :meltdowner :min-exp 1.0}])
