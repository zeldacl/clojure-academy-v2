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
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private ticks-min      20)
(def ^:private ticks-max      40)
(def ^:private ticks-tolerant 100)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :meltdowner :exp]
                  0.0)))

(defn- time-rate [ct]
  (bal/lerp 0.8 1.2 (/ (- (double ct) 20.0) 20.0)))

(defn- to-charge-ticks [ticks]
  (int (min ticks-max (max ticks-min (int ticks)))))

;; ---------------------------------------------------------------------------
;; Overload floor enforcement
;; ---------------------------------------------------------------------------

(defn- enforce-overload-floor!
  [player-id floor-value]
  (ps/update-resource-data!
   player-id
   (fn [res-data]
     (if (< (double (get res-data :cur-overload 0.0)) (double floor-value))
       (-> res-data
           (rdata/set-cur-overload floor-value)
           (assoc :overload-fine true))
       res-data))))

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
    (when-let [state (ps/get-player-state target-player-id)]
      (let [exp        (get-in state [:ability-data :skills :vec-reflection :exp] 0.0)
            consumption (* (double incoming-damage) (bal/lerp 20.0 15.0 exp))
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
            end (geom/v+ start-pos (geom/v* dir 10.0))]
        (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-reflect
                                 {:mode  :reflect
                                  :start start-pos
                                  :end   end})
        (let [hit (when raycast/*raycast*
                    (raycast/raycast-entities raycast/*raycast*
                                             world-id
                                             (:x start-pos) (:y start-pos) (:z start-pos)
                                             (:dx look-vec) (:dy look-vec) (:dz look-vec)
                                             10.0))]
          (when (and (= (:hit-type hit) :entity) entity-damage/*entity-damage*)
            (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                               world-id (:uuid hit)
                                               (* 0.5 (bal/lerp 20.0 50.0 caster-exp))
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
        damage   (* (time-rate ct) (bal/lerp 18.0 50.0 exp))
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
                    [:beam {:radius          (bal/lerp 2.0 3.0 exp)
                            :query-radius    30.0
                            :step            0.9
                            :max-distance    50.0
                            :visual-distance 45.0
                            :damage          damage
                            :damage-type     :magic
                            :break-blocks?   true
                            :block-energy    (* (time-rate ct) (bal/lerp 300.0 700.0 exp))
                            :fx-topic        :meltdowner/fx-perform}])]
        (or (:beam-result result) {:performed? false})))))

;; ---------------------------------------------------------------------------
;; Action hooks
;; ---------------------------------------------------------------------------

(defn- meltdowner-on-down!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when cost-ok?
    (let [overload-floor (bal/lerp 200.0 170.0 (skill-exp player-id))]
      (ctx/update-context! ctx-id update :skill-state assoc :overload-floor overload-floor))))

(defn- meltdowner-on-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ticks (long (or hold-ticks 0))]
    (when-let [floor (get-in (ctx/get-context ctx-id) [:skill-state :overload-floor])]
      (enforce-overload-floor! player-id floor))
    (when (> ticks ticks-tolerant)
      (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? false})
      (ctx/terminate-context! ctx-id nil)
      (log/debug "Meltdowner aborted: over tolerant ticks" ticks))))

(defn- meltdowner-on-up!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ticks (long (or hold-ticks 0))
        exp   (skill-exp player-id)]
    (if (< ticks ticks-min)
      (do
        (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? false})
        (log/debug "Meltdowner: insufficient charge ticks" ticks))
      (let [{:keys [performed? reflection-hit?]}
            (perform-meltdowner! {:player-id  player-id
                                  :ctx-id     ctx-id
                                  :hold-ticks ticks})]
        (if performed?
          (let [ct (to-charge-ticks ticks)]
            (skill-effects/add-skill-exp! player-id :meltdowner (* (time-rate ct) 0.002))
            (skill-effects/set-main-cooldown! player-id :meltdowner
                                             (int (* (time-rate ct) 20.0 (bal/lerp 15.0 7.0 exp))))
            (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? true})
            (log/debug "Meltdowner performed; reflection?" (boolean reflection-hit?)))
          (do
            (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? false})
            (log/debug "Meltdowner: beam failed")))))))

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
  :level           4
  :controllable?   true
  :ctrl-id         :meltdowner
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks  1
  :pattern         :charge-window
  :cooldown        {:mode :manual}
  :cost            {:down {:overload (fn [{:keys [player-id]}]
                                       (bal/lerp 200.0 170.0 (skill-exp player-id)))}
                    :tick {:cp (fn [{:keys [player-id]}]
                                 (bal/lerp 10.0 15.0 (skill-exp player-id)))}}
  :fx              {:start  {:topic   :meltdowner/fx-start
                             :payload (fn [_] {:mode :start})}
                    :update {:topic   :meltdowner/fx-update
                             :payload (fn [{:keys [hold-ticks]}]
                                        (let [ticks (long (or hold-ticks 0))]
                                          {:ticks        ticks
                                           :charge-ratio (bal/clamp01
                                                          (/ (double ticks)
                                                             (double ticks-max)))}))}}
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
  :prerequisites   [{:skill-id :arc-gen :min-exp 1.0}
                    {:skill-id :railgun :min-exp 0.5}])
