(ns cn.li.ac.content.ability.meltdowner.light-shield
  "LightShield skill - toggle energy barrier that absorbs damage and pushes enemies.

  Pattern: :toggle
  Activation cost: CP lerp(200, 160, exp), overload lerp(100, 70, exp)
  Tick cost: CP lerp(12, 8, exp) per tick
  Damage reduction: lerp(0.5, 0.8, exp) of incoming damage
  Touch damage: lerp(3, 8, exp) to entities in front 60° cone within 3 blocks
  On deactivate: slowness II for 60 ticks
  Cooldown: lerp(100, 60, exp) ticks (manual)
  Exp: +0.0004 per damage absorbed

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private light-shield-skill-id :light-shield)
(def ^:private light-shield-state-key :light-shield)

(defn- cfg-double [field-id]
  (skill-config/tunable-double light-shield-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int light-shield-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double light-shield-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int light-shield-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id light-shield-skill-id))

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
  (when-let [raycast (resolve 'cn.li.mcmod.platform.raycast/*raycast*)]
    (when-let [rc-impl @raycast]
      ((resolve 'cn.li.mcmod.platform.raycast/get-player-look-vector)
       rc-impl player-id))))

(defn- enforce-overload-floor!
  [player-id ctx-data]
  (when-let [floor (get-in ctx-data (state-path :overload-floor))]
    (skill-effects/enforce-overload-floor! player-id floor)))

(defn- safe-context-data
  [ctx-id]
  (try
    (ctx/get-context ctx-id)
    (catch Exception _ nil)))

(defn- command-runtime-ready?
  [{:keys [session-id player-uuid]}]
  (and (runtime-hooks/current-player-state-owner)
       session-id
       player-uuid))

(defn- set-shield-state-path!
  [ctx-id ks v]
  (let [ctx-data (or (safe-context-data ctx-id) {})
        key-path (into [light-shield-state-key] ks)]
    (if (command-runtime-ready? ctx-data)
      (let [result (command-rt/run-command-in-session! (:session-id ctx-data)
                                                       (:player-uuid ctx-data)
                                                       {:command :context-assoc-skill-state
                                                        :ctx-id ctx-id
                                                        :k key-path
                                                        :v v})]
        (when (= :context-not-found (:rejected-reason result))
          (ctx/update-context! ctx-id assoc-in (into [:skill-state] key-path) v)))
      (ctx/update-context! ctx-id assoc-in (into [:skill-state] key-path) v))))

(defn- update-skill-state-root!
  [ctx-id f]
  (let [ctx-data (or (safe-context-data ctx-id) {})
        next-state (f (or (:skill-state ctx-data) {}))]
    (if (command-runtime-ready? ctx-data)
      (let [result (command-rt/run-command-in-session! (:session-id ctx-data)
                                                       (:player-uuid ctx-data)
                                                       {:command :context-assoc-skill-state
                                                        :ctx-id ctx-id
                                                        :k []
                                                        :v next-state})]
        (when (= :context-not-found (:rejected-reason result))
          (ctx/update-context! ctx-id assoc :skill-state next-state)))
      (ctx/update-context! ctx-id assoc :skill-state next-state))))

(defn- get-player-position [player-id]
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn- in-front-cone?
  "Check if entity is within 60° cone in front of player."
  [player-pos player-look entity-pos]
  (when (and player-pos player-look entity-pos)
    (let [dx (- (double (:x entity-pos)) (double (:x player-pos)))
          dy (- (double (:y entity-pos)) (double (:y player-pos)))
          dz (- (double (:z entity-pos)) (double (:z player-pos)))
          len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))
          nx (/ dx len) ny (/ dy len) nz (/ dz len)
          dot (+ (* nx (double (:x player-look)))
                 (* ny (double (:y player-look)))
                 (* nz (double (:z player-look))))]
      (> dot (cfg-double :combat.front-cone-dot)))))  ; cos(60°) = 0.5

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn light-shield-activate!
  [{:keys [ctx-id player-id]}]
  (let [overload-floor (double (or (skill-effects/player-path player-id [:resource-data :cur-overload] 0.0) 0.0))]
    (set-shield-state-path! ctx-id [:ticks] 0)
    (set-shield-state-path! ctx-id [:last-absorb-tick] (- (cfg-int :combat.absorb-interval-ticks)))
    (set-shield-state-path! ctx-id [:last-touch-tick] (- (cfg-int :combat.touch-interval-ticks)))
    (set-shield-state-path! ctx-id [:overload-floor] overload-floor))
  (log/info "LightShield: Activated"))

(defn light-shield-deactivate!
  [{:keys [player-id ctx-id]}]
  (when potion-effects/*potion-effects*
    (potion-effects/apply-potion-effect!
      potion-effects/*potion-effects*
      player-id :slowness
      (cfg-int :effect.deactivate-slowness-duration-ticks)
      (cfg-int :effect.slowness-amplifier)))
  (let [exp (skill-exp player-id)]
    (skill-effects/set-main-cooldown!
      player-id light-shield-skill-id
      (cfg-lerp-int :cooldown.ticks exp)))
  (update-skill-state-root! ctx-id #(dissoc % light-shield-state-key))
  (log/info "LightShield: Deactivated"))

(defn- maybe-touch-damage!
  [{:keys [ctx-id player-id exp pos world-id look-vec ticks]}]
  (when (and pos world-effects/*world-effects*)
    (let [entities (world-effects/find-entities-in-radius
                    world-effects/*world-effects*
                    world-id (:x pos) (:y pos) (:z pos)
                    (cfg-double :combat.touch-radius))]
      (doseq [entity entities]
        (when (and (:living? entity)
                   (not= (:uuid entity) player-id)
                   (in-front-cone? pos look-vec entity)
                   (consume-absorb! player-id exp))
          (when entity-damage/*entity-damage*
            (md-damage/mark-target! player-id (:uuid entity)
                                    {:ctx-id ctx-id
                                     :target-pos {:x (:x entity)
                                                  :y (:y entity)
                                                  :z (:z entity)}})
            (entity-damage/apply-direct-damage!
             entity-damage/*entity-damage*
             world-id (:uuid entity)
             (cfg-lerp :combat.touch-damage exp)
             :magic)
            (skill-effects/add-skill-exp!
             player-id light-shield-skill-id
             (* (cfg-lerp :combat.touch-damage exp)
                (cfg-double :progression.exp-absorbed-scale))))))
          (set-shield-state-path! ctx-id [:last-touch-tick] ticks))))

(defn light-shield-tick!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (when (and cost-ok? (toggle/is-toggle-active? ctx-data :light-shield))
      (let [exp (skill-exp player-id)
            next-ticks (inc (shield-ticks ctx-data))
            max-active (cfg-lerp-int :timing.max-active-ticks exp)
            pos (get-player-position player-id)
            world-id (or (:world-id pos)
                         (skill-effects/player-path player-id [:position :world-id])
                         "minecraft:overworld")
            look-vec (get-player-look-vector player-id)
            touch-interval (cfg-int :combat.touch-interval-ticks)
            last-touch (long (or (get-in ctx-data (state-path :last-touch-tick)) (- touch-interval)))]
        (set-shield-state-path! ctx-id [:ticks] next-ticks)
        (enforce-overload-floor! player-id ctx-data)
        (if (> next-ticks max-active)
          (do
            (toggle/remove-toggle! ctx-id :light-shield)
            (light-shield-deactivate! {:player-id player-id :ctx-id ctx-id}))
          (when (>= (- next-ticks last-touch) touch-interval)
            (maybe-touch-damage!
             {:ctx-id ctx-id
              :player-id player-id
              :exp exp
              :ticks next-ticks
              :pos pos
              :world-id world-id
              :look-vec look-vec})))))))

(defn light-shield-abort!
  [{:keys [ctx-id player-id]}]
  (when potion-effects/*potion-effects*
    (potion-effects/apply-potion-effect!
      potion-effects/*potion-effects*
      player-id :slowness
      (cfg-int :effect.abort-slowness-duration-ticks)
      (cfg-int :effect.slowness-amplifier)))
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
          entities (when (and pos world-effects/*world-effects*)
                     (world-effects/find-entities-in-radius
                      world-effects/*world-effects*
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
        (if (or (< (- ticks last-absorb) interval)
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
  (md-damage/init!)
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
  :ui-position    [155 140]
  :level          2
  :controllable?  true
  :ctrl-id        :light-shield
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1
  :pattern        :toggle
  :cooldown       {:mode :manual}
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
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
