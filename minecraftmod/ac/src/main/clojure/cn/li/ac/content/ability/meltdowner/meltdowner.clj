(ns cn.li.ac.content.ability.meltdowner.meltdowner
  "Meltdowner skill – charge-window beam with block-breaking and reflection.

  Uses the escape-hatch pattern: fn hooks for overload-floor enforcement and
  min/max charge-window gating; :beam op (effect.beam) for the actual shot.

  Key mechanics:
  - Charge window: min 20, optimal cap 40, tolerant max 100 ticks
  - Down cost: overload lerp(200,170,exp)
  - Tick cost: CP lerp(10,15,exp)
  - Beam: width lerp(2,3,exp), damage timeRate(ct)*lerp(18,50,exp)
  - Block energy: timeRate(ct)*lerp(300,700,exp)
  - Reflection: delegates to vec-reflection via :reflect-can-fn/:reflect-shot-fn
  - Cooldown: timeRate(ct) * 20 * lerp(15,7,exp)  (manual)
  - EXP gain: timeRate(ct) * 0.002

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Config helpers
;; ---------------------------------------------------------------------------

(def ^:private meltdowner-skill-id :meltdowner)

(defn- cfg-double [field-id]
  (skill-config/tunable-double meltdowner-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int meltdowner-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double meltdowner-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int meltdowner-skill-id field-id exp))

(defn- ticks-min [] (cfg-int :charge.min-ticks))
(defn- ticks-max [] (cfg-int :charge.max-ticks))
(defn- ticks-tolerant [] (cfg-int :charge.max-tolerant-ticks))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id meltdowner-skill-id))

(defn- time-rate [ct]
  (let [charge-window (max 1.0 (- (double (ticks-max)) (double (ticks-min))))]
    (cfg-lerp :charge.time-rate (/ (- (double ct) (double (ticks-min))) charge-window))))

(defn- to-charge-ticks [ticks]
  (int (min (ticks-max) (max (ticks-min) (int ticks)))))

;; ---------------------------------------------------------------------------
;; Overload floor enforcement
;; ---------------------------------------------------------------------------

(defn- enforce-overload-floor!
  [player-id floor-value]
  (skill-effects/enforce-overload-floor! player-id floor-value))

;; ---------------------------------------------------------------------------
;; Vec-reflection interaction
;; ---------------------------------------------------------------------------

(defn- toggle-active? [player-id skill-id]
  (some (fn [[_ ctx-data]]
          (and (= (:player-id ctx-data) player-id)
               (toggle/is-toggle-active? ctx-data skill-id)))
        (ctx/get-all-contexts)))

(defn- vec-reflection-can-reflect? [target-player-id incoming-damage]
  (when (toggle-active? target-player-id :vec-reflection)
    (when-let [state (skill-effects/get-player-state target-player-id)]
      (let [exp        (skill-effects/skill-exp target-player-id :vec-reflection)
        consumption (* (double incoming-damage) (cfg-lerp :reflection.cp-per-damage exp))
            current-cp (get-in state [:resource-data :cur-cp] 0.0)]
        (>= (double current-cp) (double consumption))))))

(defn- perform-reflection-shot!
  "Fire a reflected shot from the reflector player's perspective.
  Returns truthy if an entity was hit."
  [ctx-id reflector-player-id caster-exp]
  (let [start-pos (geom/eye-pos reflector-player-id)
        world-id  (geom/world-id-of reflector-player-id)
        look-vec  (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* reflector-player-id))]
    (when look-vec
      (let [dir (geom/vnorm {:x (:dx look-vec) :y (:dy look-vec) :z (:dz look-vec)})
            end (geom/v+ start-pos (geom/v* dir (cfg-double :reflection.shot-distance)))]
        (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-reflect
                                 {:mode  :reflect
                                  :start start-pos
                                  :end   end})
        (let [hit (when raycast/*raycast*
                    (raycast/raycast-entities raycast/*raycast*
                                             world-id
                                             (:x start-pos) (:y start-pos) (:z start-pos)
                                             (:dx look-vec) (:dy look-vec) (:dz look-vec)
                                             (cfg-double :reflection.shot-distance)))]
          (when (and (= (:hit-type hit) :entity) entity-damage/*entity-damage*)
            (md-damage/mark-target! reflector-player-id (:uuid hit))
            (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                               world-id (:uuid hit)
                                                (* (cfg-double :reflection.damage-multiplier)
                                                  (cfg-lerp :combat.damage caster-exp))
                                               :magic)
            true))))))

;; ---------------------------------------------------------------------------
;; Main beam shot
;; ---------------------------------------------------------------------------

(defn- perform-meltdowner!
  "Fires the meltdowner beam. Returns :beam-result map (or {:performed? false})."
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [exp      (skill-exp player-id)
        ct       (to-charge-ticks hold-ticks)
      damage   (* (time-rate ct) (cfg-lerp :combat.damage exp))
        world-id (geom/world-id-of player-id)
        eye      (geom/eye-pos player-id)
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (if-not look-vec
      {:performed? false}
      (let [result (effect/run-op!
                    {:player-id       player-id
                     :ctx-id          ctx-id
                     :world-id        world-id
                     :eye-pos         eye
                     :look-dir        look-vec
                     :reflect-can-fn  (fn [uuid] (vec-reflection-can-reflect? uuid damage))
                     :reflect-shot-fn (fn [uuid] (perform-reflection-shot! ctx-id uuid exp))}
                        [:beam {:radius          (cfg-lerp :beam.radius exp)
                          :query-radius    (cfg-double :beam.query-radius)
                          :step            (cfg-double :beam.step)
                          :max-distance    (cfg-double :beam.max-distance)
                          :visual-distance (cfg-double :beam.visual-distance)
                            :damage          damage
                            :damage-type     :magic
                            :break-blocks?   true
                            :block-energy    (* (time-rate ct) (cfg-lerp :beam.block-energy exp))
                            :fx-topic        :meltdowner/fx-perform}])]
        (or (:beam-result result) {:performed? false})))))

;; ---------------------------------------------------------------------------
;; Action hooks
;; ---------------------------------------------------------------------------

(defn- meltdowner-on-down!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when cost-ok?
    (let [overload-floor (cfg-lerp :cost.down.overload (skill-exp player-id))]
      (ctx/update-context! ctx-id update :skill-state assoc :overload-floor overload-floor))))

(defn- meltdowner-on-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ticks (long (or hold-ticks 0))]
    (when-let [floor (get-in (ctx/get-context ctx-id) [:skill-state :overload-floor])]
      (enforce-overload-floor! player-id floor))
    (when (> ticks (ticks-tolerant))
      (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? false})
      (ctx/terminate-context! ctx-id nil)
      (log/debug "Meltdowner aborted: over tolerant ticks" ticks))))

(defn- meltdowner-on-up!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ticks (long (or hold-ticks 0))
        exp   (skill-exp player-id)]
      (if (< ticks (ticks-min))
      (do
        (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? false})
        (log/debug "Meltdowner: insufficient charge ticks" ticks))
      (let [{:keys [performed? reflection-hit?]}
            (perform-meltdowner! {:player-id  player-id
                                  :ctx-id     ctx-id
                                  :hold-ticks ticks})]
        (if performed?
          (let [ct (to-charge-ticks ticks)]
            (skill-effects/add-skill-exp! player-id meltdowner-skill-id
                                          (* (time-rate ct) (cfg-double :progression.exp-use)))
            (skill-effects/set-main-cooldown! player-id meltdowner-skill-id
                                             (int (* (time-rate ct)
                                                     (cfg-double :cooldown.base-multiplier)
                                                     (cfg-lerp :cooldown.ticks exp))))
            (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? true})
            (log/debug "Meltdowner performed; reflection?" (boolean reflection-hit?)))
          (do
            (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? false})
            (log/debug "Meltdowner: beam failed")))))))

(defn init!
  "Explicit runtime installer for Meltdowner shared damage helper hooks."
  []
  (md-damage/init!)
  nil)

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! meltdowner
  :id              :meltdowner
  :category-id     :meltdowner
  :name-key        "ability.skill.meltdowner.meltdowner"
  :description-key "ability.skill.meltdowner.meltdowner.desc"
  :icon            "textures/abilities/meltdowner/skills/meltdowner.png"
  :ui-position     [128 80]
  :level           3
  :controllable?   true
  :ctrl-id         :meltdowner
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks  1
  :pattern         :charge-window
  :cooldown        {:mode :manual}
  :cost            {:down {:overload (fn [{:keys [player-id]}]
                 (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                    :tick {:cp (fn [{:keys [player-id]}]
               (cfg-lerp :cost.tick.cp (skill-exp player-id)))} }
  :fx              {:start  {:topic   :meltdowner/fx-start
                             :payload (fn [_] {:mode :start})}
                    :update {:topic   :meltdowner/fx-update
                             :payload (fn [{:keys [hold-ticks]}]
                                        (let [ticks (long (or hold-ticks 0))]
                                          {:ticks        ticks
                                           :charge-ratio (bal/clamp01
                                                          (/ (double ticks)
                                                       (double (ticks-max))))}))}}
  :actions         {:down!      meltdowner-on-down!
                    :tick!      meltdowner-on-tick!
                    :up!        meltdowner-on-up!
                    :abort!     (fn [{:keys [ctx-id]}]
                                  (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end
                                                           {:performed? false}))
                    :cost-fail! (fn [{:keys [ctx-id cost-stage]}]
                                  (when (= cost-stage :tick)
                                    (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end
                                                             {:performed? false}))
                                  (ctx/terminate-context! ctx-id nil))}
  :prerequisites   [{:skill-id :scatter-bomb :min-exp 0.8}
                    {:skill-id :light-shield :min-exp 0.8}])
