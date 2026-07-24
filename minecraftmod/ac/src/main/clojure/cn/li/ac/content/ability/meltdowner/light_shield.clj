(ns cn.li.ac.content.ability.meltdowner.light-shield
  "LightShield skill - toggle energy barrier that absorbs damage and pushes enemies.

  Pattern: :toggle
  Activation cost: overload lerp(110, 60, exp), no CP
  Tick cost: CP lerp(9, 4, exp) per tick
  Damage reduction: lerp(0.5, 0.8, exp) of incoming damage
  Touch damage: lerp(2, 6, exp) to entities in front 60° yaw cone within 3 blocks
  On deactivate or abort: slowness II for 100 ticks, cooldown = ticks-held * (2 - exp)
  Exp: damage-absorbed * 0.0004 (touch and defensive absorb alike)

  No Minecraft imports."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
                        [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.ac.ability.effects.potion :as potion-effects]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.ac.ability.effects.motion :as motion-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def-skill-config-ops :light-shield)
(def ^:private light-shield-skill-id :light-shield)
(def ^:private light-shield-state-key :light-shield)

(defn- state-path
  [& ks]
  (into [:skill-state light-shield-state-key] ks))

(defn- active-light-shield-entry
  [player-id]
  (some (fn [[ctx-key ctx-data]]
          (when (and (= (:player-uuid ctx-data) player-id)
                     (toggle/is-toggle-active? ctx-data :light-shield))
            [ctx-key ctx-data]))
        (ctx/get-all-contexts)))

(defn- shield-ticks
  [ctx-data]
  (long (or (get-in ctx-data (state-path :ticks)) 0)))

(defn- absorb-overload-cost
  [exp]
  (cfg-lerp :cost.absorb.overload exp))

(defn- absorb-cp-cost
  [exp]
  (cfg-lerp :cost.absorb.cp exp))

(defn- consume-absorb!
  [player-id exp]
  (boolean
    (:success?
      (skill-effects/perform-resource!
        player-id
        (absorb-overload-cost exp)
        (absorb-cp-cost exp)
        false))))

(defn- get-player-look-vector
  [player-id]
  (raycast/player-look-vector player-id))

(defn- enforce-overload-floor!
  [player-id ctx-data]
  (when-let [floor (get-in ctx-data (state-path :overload-floor))]
    (skill-effects/enforce-overload-floor! player-id floor)))

(defn- set-shield-state-path!
  [ctx-id ks v]
  (ctx-skill/assoc-skill-state! ctx-id (into [light-shield-state-key] ks) v))

(defn- update-skill-state-root!
  [ctx-id f]
  (ctx-skill/update-skill-state-root! ctx-id f))

(defn- get-player-position [player-id]
  (motion-effects/player-position player-id))

(defn- horizontal-yaw-degrees
  [x z]
  (- (Math/toDegrees (Math/atan2 (double x) (double z)))))

;; Matches original's isEntityReachable: a horizontal-yaw-only comparison
;; (dy is explicitly ignored in the original — pitch/height never affect the
;; cone), not a full 3D dot-product cone.
(defn- in-front-cone?
  [player-pos player-look entity-pos]
  (when (and player-pos player-look entity-pos)
    (let [dx (- (double (:x entity-pos)) (double (:x player-pos)))
          dz (- (double (:z entity-pos)) (double (:z player-pos)))
          player-yaw (horizontal-yaw-degrees (:x player-look) (:z player-look))
          target-yaw (horizontal-yaw-degrees dx dz)
          diff (mod (Math/abs (double (- target-yaw player-yaw))) 360.0)]
      (< diff (cfg-double :combat.front-cone-degrees)))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn light-shield-activate!
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage player-ref]
  (let [overload-floor (double (or (skill-effects/player-path player-id [:resource-data :cur-overload] 0.0) 0.0))]
    (set-shield-state-path! ctx-id [:ticks] 0)
    (set-shield-state-path! ctx-id [:last-absorb-tick] (- (cfg-int :combat.absorb-interval-ticks)))
    (set-shield-state-path! ctx-id [:overload-floor] overload-floor)
    ;; Spawn EntityMdShield visual (matching original c_spawn: new EntityMdShield(player))
    (when player-ref
      (entity/player-spawn-entity-by-id! player-ref (modid/namespaced-path "entity_md_shield") 0.0)))
  (log/info "LightShield: Activated"))

;; Matches original getCooldown(ct) = lerp(2*ct, ct, exp): cooldown scales
;; with how long the shield was actually held (ticks), not a flat exp range.
(defn- toggle-cooldown-ticks
  [ticks exp]
  (long (Math/round (* (double ticks) (- 2.0 (double exp))))))

;; Matches original's unified s_onEnd (MSG_TERMINATED): both voluntary
;; deactivation and forced abort funnel through the same slowness + cooldown
;; application, regardless of which path ended the toggle.
(defn- end-shield!
  [ctx-id player-id exp]
  (let [ticks (shield-ticks (ctx-skill/get-context ctx-id))]
    (when (potion-effects/available?)
      (potion-effects/apply-effect!
        player-id :slowness
        (cfg-int :effect.slowness-duration-ticks)
        (cfg-int :effect.slowness-amplifier)))
    (skill-effects/set-main-cooldown!
      player-id light-shield-skill-id
      (toggle-cooldown-ticks ticks exp))))

(defn light-shield-deactivate!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (end-shield! ctx-id player-id exp)
  (update-skill-state-root! ctx-id #(dissoc % light-shield-state-key))
  (log/info "LightShield: Deactivated"))

;; Matches original's per-tick attempt (no interval throttle) — the only
;; original gate is target invulnerability (e.hurtResistantTime<=0), which
;; apply-direct-damage! already respects via the vanilla hurt() call, plus
;; whether the resource cost can be paid.
(defn- maybe-touch-damage!
  [{:keys [ctx-id player-id exp pos world-id look-vec]}]
  (when (and pos (world-effects/available?))
    (let [entities (world-effects/find-entities-in-radius
                    world-id (:x pos) (:y pos) (:z pos)
                    (cfg-double :combat.touch-radius))]
      (doseq [entity entities]
        (when (and (:living? entity)
                   (not= (:uuid entity) player-id)
                   (in-front-cone? pos look-vec entity)
                   (consume-absorb! player-id exp))
          (when (entity-damage/available?)
            (md-damage/mark-target! player-id (:uuid entity)
                                    {:ctx-id ctx-id
                                     :target-pos {:x (:x entity)
                                                  :y (:y entity)
                                                  :z (:z entity)}})
            (entity-damage/apply-direct-damage!
             world-id (:uuid entity)
             (cfg-lerp :combat.touch-damage exp)
             :magic)
            (skill-effects/add-skill-exp!
             player-id light-shield-skill-id
             (* (cfg-lerp :combat.touch-damage exp)
                (cfg-double :progression.exp-absorbed-scale)))))))))

(defn light-shield-tick!
  [ctx-id player-id _skill-id exp cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (when (and cost-ok? (toggle/is-toggle-active? ctx-data :light-shield))
      (let [next-ticks (inc (shield-ticks ctx-data))
            max-active (cfg-lerp-int :timing.max-active-ticks exp)
            pos (get-player-position player-id)
            world-id (or (:world-id pos)
                         (skill-effects/player-path player-id [:position :world-id])
                         "minecraft:overworld")
            look-vec (get-player-look-vector player-id)]
        (set-shield-state-path! ctx-id [:ticks] next-ticks)
        (enforce-overload-floor! player-id ctx-data)
        (if (> next-ticks max-active)
          (do
            (toggle/remove-toggle! ctx-id :light-shield)
            (light-shield-deactivate! ctx-id player-id nil exp cost-ok? 0 nil nil))
          (maybe-touch-damage!
           {:ctx-id ctx-id
            :player-id player-id
            :exp exp
            :pos pos
            :world-id world-id
            :look-vec look-vec}))))))

(defn light-shield-abort!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (end-shield! ctx-id player-id exp)
  (toggle/remove-toggle! ctx-id :light-shield)
  (update-skill-state-root! ctx-id #(dissoc % light-shield-state-key)))

;; ---------------------------------------------------------------------------
;; Damage reduction handler
;; ---------------------------------------------------------------------------

(defn- attacker-front?
  [player-id attacker-id]
  (if (nil? attacker-id)
    true
    (let [pos (get-player-position player-id)
          look-vec (get-player-look-vector player-id)
          world-id (or (:world-id pos)
                       (skill-effects/player-path player-id [:position :world-id])
                       "minecraft:overworld")
          entities (when (and pos (world-effects/available?))
                     (world-effects/find-entities-in-radius
                      world-id (:x pos) (:y pos) (:z pos)
                      (cfg-double :combat.touch-radius)))
          attacker (some #(when (= (str (:uuid %)) (str attacker-id)) %) entities)]
      (boolean (and attacker (in-front-cone? pos look-vec attacker))))))

(defn- light-shield-reduce-damage
  [player-id attacker-id damage _damage-source]
  (try
    (if-let [[ctx-key ctx-data] (active-light-shield-entry player-id)]
      (let [ticks (shield-ticks ctx-data)
            last-absorb (long (or (get-in ctx-data (state-path :last-absorb-tick)) (- (cfg-int :combat.absorb-interval-ticks))))
            interval (cfg-int :combat.absorb-interval-ticks)]
        ;; Matches original's <= (skip when diff<=interval, i.e. requires
        ;; interval+1 ticks since the last absorb, not just interval ticks).
        (if (or (<= (- ticks last-absorb) interval)
                (not (attacker-front? player-id attacker-id)))
          [damage nil]
          (let [exp (skill-exp player-id)]
            (if-not (consume-absorb! player-id exp)
              [damage nil]
              (let [absorb-cap (cfg-lerp :combat.absorb-damage exp)
                    absorbed (double (min (double damage) absorb-cap))
                    new-damage (double (- (double damage) absorbed))]
                (set-shield-state-path! ctx-key [:last-absorb-tick] ticks)
                (skill-effects/add-skill-exp! player-id light-shield-skill-id
                                              (* absorbed (cfg-double :progression.exp-absorbed-scale)))
                [new-damage {:absorbed absorbed}])))))
      [damage nil])
    (catch Exception e
      (log/warn "LightShield reduce-damage failed:" (ex-message e))
      [damage nil])))

(defn init!
  []
  (damage-handler/register-toggle-damage-handler!
    :light-shield-damage
    :light-shield
    light-shield-reduce-damage
    80)
  nil)

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill light-shield
  :id             :light-shield
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.light_shield"
  :description-key "ability.skill.meltdowner.light_shield.desc"
  :icon           "textures/abilities/meltdowner/skills/light_shield.png"
  :ui-position    [55 15]
  :ctrl-id        :light-shield
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  ;; Matches original getCooldown(ct) = lerp(2*ct, ct, exp) — cooldown scales
  ;; with actual shield uptime, so this display estimate is 0 before the
  ;; shield has ever been held (ticks defaults to 0).
  :cooldown-ticks (fn [{:keys [player-id ctx-id]}]
                    (let [exp (skill-exp player-id)
                          ticks (long (or (some-> ctx-id ctx-skill/get-context (get-in [:skill-state light-shield-state-key :ticks])) 0))]
                      (toggle-cooldown-ticks ticks exp)))
  :pattern        :toggle
  :cooldown       {:mode :manual}
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
              (cfg-lerp :cost.tick.cp (skill-exp player-id)))} }
  :actions        {:activate!   light-shield-activate!
                   :deactivate! light-shield-deactivate!
                   :tick!       light-shield-tick!
                   :abort!      light-shield-abort!}
  :fx             {:start {:topic :light-shield/fx-start :payload (fn [_] {})}
                   :end   {:topic :light-shield/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :electron-bomb :min-exp 1.0}])
