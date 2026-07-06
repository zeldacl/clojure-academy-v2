(ns cn.li.ac.content.ability.vecmanip.vec-deviation
  "VecDeviation skill - passive projectile deflection (toggle).

  Mechanics:
  - Toggle skill - stays active until deactivated or resources depleted
  - Deflects incoming projectiles (stops motion)
  - Reduces incoming damage by 40-90% (scales with experience)
  - Tracks visited entities to avoid duplicate processing
  - Marks deflected entities to prevent re-deflection
  - CP drain: 13-5 per tick (passive)
  - CP per projectile: 15-12 (scales with experience)
  - CP per damage: 15-12 (scales with experience)
  - Experience gain: 0.0006 per damage point deflected
  - No overload cost

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.content.ability.vecmanip.arbitration :as arbitration]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.skill-effects :as fx-common]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
                        [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def-skill-config-ops :vec-deviation)
(def ^:private vec-deviation-skill-id :vec-deviation)

(defn- parse-difficulty-entry [entry]
  (try
    (let [entry* (str entry)
          idx (.lastIndexOf ^String entry* ":")]
      (when (pos? idx)
        [(subs entry* 0 idx)
         (Double/parseDouble (subs entry* (inc idx)))]))
    (catch Exception _
      nil)))

(defn- affected-entity-difficulty []
  (into {}
        (keep parse-difficulty-entry)
        (skill-config/tunable-string-list vec-deviation-skill-id :targeting.affected-entity-difficulty)))

(defn- excluded-entity-ids []
  (cfg-string-set :targeting.excluded-entity-ids))

(defn- large-fireball-ids []
  (cfg-string-set :targeting.large-fireball-ids))

(defn- small-fireball-ids []
  (cfg-string-set :targeting.small-fireball-ids))

(defn- current-cp
  [player-id]
  (fx-common/current-cp player-id))

(defn- consume-cp!
  [player-id cp]
  (boolean (:success? (fx-common/perform-resource! player-id 0.0 (double cp) false))))

(defn vec-deviation-cost-tick-cp
  [_player-id _skill-id exp]
  (cfg-lerp :cost.tick.cp (double (or exp 0.0))))

(defn- get-player-position
  "Get player position from teleportation protocol."
  [player-id]
  (teleportation/get-player-position* player-id))

(defn- entity-registry-id
  [entity]
  (or (:entity-id entity) (:type entity) ""))

(defn- excluded-entity?
  [entity]
  (let [eid (entity-registry-id entity)]
    (or (contains? (excluded-entity-ids) eid)
        (:item? entity)
        (:living? entity)
        (:mob? entity))))

(defn- affect-difficulty
  [entity]
  (let [eid (entity-registry-id entity)]
    (when-not (excluded-entity? entity)
      (double (get (affected-entity-difficulty) eid 1.0)))))

(defn- active-vec-deviation-ctx-id
  [player-id]
  (->> (ctx/get-all-contexts)
       (filter (fn [[_ctx-id ctx-data]]
                 (and (= (:player-uuid ctx-data) player-id)
                      (toggle/is-toggle-active? ctx-data :vec-deviation))))
       first
       first))

(defn- set-skill-state-key!
  [ctx-id k v]
  (ctx-skill/assoc-skill-state! ctx-id k v))

(defn- update-skill-state-root!
  [ctx-id f]
  (ctx-skill/update-skill-state-root! ctx-id f))

(defn- add-exp!
  [player-id amount]
  (fx-common/add-skill-exp! player-id :vec-deviation amount))

(defn- send-fx-stop-entity! [ctx-id entity marked?]
  (fx/send! ctx-id {:topic :vec-deviation/fx-stop-entity :mode :stop-entity} nil
            {:x (double (or (:x entity) 0.0))
             :y (double (or (:y entity) 0.0))
             :z (double (or (:z entity) 0.0))
             :marked? (boolean marked?)}))

(defn- send-fx-play! [ctx-id pos]
  (fx/send! ctx-id {:topic :vec-deviation/fx-play :mode :play} nil
            {:x (double (or (:x pos) 0.0))
             :y (double (or (:y pos) 0.0))
             :z (double (or (:z pos) 0.0))}))

(defn- entity-uuid-not-in-set?
  "True when entity :uuid is absent from visited set (Iron Rule 13 safe for remove)."
  [visited entity]
  (not (contains? visited (:uuid entity))))

;; ============================================================================
;; DSL actions (used by :pattern :toggle)
;; ============================================================================

(defn vec-deviation-activate!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [exp             (double (or exp 0.0))
        activ-overload  (cfg-lerp :cost.activation.overload exp)
        cur-overload    (fx-common/player-path player-id [:resource-data :cur-overload] 0.0)
        overload-floor  (+ (double cur-overload) activ-overload)]
    (fx-common/perform-resource! player-id activ-overload 0.0 false)
    (set-skill-state-key! ctx-id :vec-deviation-visited #{})
    (set-skill-state-key! ctx-id :vec-deviation-marked #{})
    (set-skill-state-key! ctx-id :vec-deviation-overload-floor overload-floor)
    (log/info "VecDeviation: Activated")))

(defn vec-deviation-deactivate!
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (update-skill-state-root! ctx-id #(dissoc % :vec-deviation-visited :vec-deviation-marked :vec-deviation-overload-floor))
  (log/info "VecDeviation: Deactivated"))

(defn vec-deviation-tick!
  "Tick handler - consume resources and deflect projectiles. Assumes toggle is active."
  [ctx-id player-id _skill-id exp cost-ok? _hold-ticks _cost-stage _player-ref]
  (try
    (when-let [ctx-data (ctx-skill/get-context ctx-id)]
      (let [exp (double (or exp 0.0))
            active? (toggle/is-toggle-active? ctx-data :vec-deviation)]
        (when (and active? (not cost-ok?))
          (toggle/deactivate-toggle! ctx-id :vec-deviation)
          (log/info "VecDeviation: Deactivated (insufficient CP)"))
        (when (and active? cost-ok?)
          ;; Edit D: enforce overload floor while skill is active
          (when-let [floor (get-in ctx-data [:skill-state :vec-deviation-overload-floor])]
            (fx-common/enforce-overload-floor! player-id floor))
          (when-let [pos (get-player-position player-id)]
            (when (world-effects/available?)
              (let [world-id (:world-id pos)
                    x (:x pos)
                    y (:y pos)
                    z (:z pos)
                    entities (world-effects/find-entities-in-radius*
                                                                    world-id x y z (cfg-double :targeting.radius))
                    visited (get-in ctx-data [:skill-state :vec-deviation-visited] #{})
                    marked (get-in ctx-data [:skill-state :vec-deviation-marked] #{})
                    dual-active? (arbitration/dual-active? player-id)
                    arbitration-allowed? (or (not dual-active?)
                                             (arbitration/skill-allowed-in-dual-active? :vec-deviation))
                    fresh-entities (remove (partial entity-uuid-not-in-set? visited)
                                           entities)]
                (doseq [entity fresh-entities]
                  (let [entity-uuid (:uuid entity)
                        eid (entity-registry-id entity)
                        difficulty (affect-difficulty entity)
                        marked? (contains? marked entity-uuid)]
                    (when (and entity-uuid
                               (not= entity-uuid player-id)
                               (not marked?)
                               difficulty
                               (toggle/is-toggle-active? (or (ctx-skill/get-context ctx-id) ctx-data) :vec-deviation))
                      ;; Edit E: fix Bug1 (no * difficulty), Bug2 (force-consume), Bug3 (consume after claim)
                      (let [base-cost   (cfg-lerp :cost.deflect.cp exp)
                            avail-cp    (current-cp player-id)
                            actual-cost (min avail-cp base-cost)]
                        (when (and arbitration-allowed?
                                   (arbitration/claim-projectile! player-id :vec-deviation entity-uuid))
                          ;; Consume only after arbitration succeeds; cap to available CP (force-consume semantics)
                          (when (pos? actual-cost)
                            (fx-common/perform-resource! player-id 0.0 actual-cost false))
                          (when (entity-motion/available?)
                            (entity-motion/set-velocity!*
                                                         world-id entity-uuid 0.0 0.0 0.0))
                          (when (or (contains? (large-fireball-ids) eid)
                                    (contains? (small-fireball-ids) eid))
                            (when (entity-motion/available?)
                              (entity-motion/discard-entity!* world-id entity-uuid)))
                          (when (and (contains? (large-fireball-ids) eid)
                                     (world-effects/available?))
                            (world-effects/create-explosion!*
                                                             world-id
                                                             (double (or (:x entity) 0.0))
                                                             (double (or (:y entity) 0.0))
                                                             (double (or (:z entity) 0.0))
                                                             (cfg-double :combat.fireball-explosion-radius)
                                                             false))
                          (add-exp! player-id (* (cfg-double :progression.exp-deflect-scale) difficulty))
                          (let [generic-mark? (and (not (contains? (large-fireball-ids) eid))
                                                   (not (contains? (small-fireball-ids) eid)))]
                            (when generic-mark?
                              (update-skill-state-root! ctx-id #(update % :vec-deviation-marked (fnil conj #{}) entity-uuid)))
                            (send-fx-stop-entity! ctx-id entity generic-mark?))
                          (log/debug "VecDeviation: Deflected entity" entity-uuid "difficulty" difficulty))))))
                (let [visited-ids (into #{} (keep #(get % :uuid) entities))]
                  (update-skill-state-root! ctx-id #(update % :vec-deviation-visited (fnil into #{}) visited-ids)))))))))
    (catch Exception e
      (log/warn "VecDeviation tick! failed:" (ex-message e)))))

(defn vec-deviation-abort!
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (toggle/remove-toggle! ctx-id :vec-deviation)
  (update-skill-state-root! ctx-id #(dissoc % :vec-deviation-visited :vec-deviation-marked :vec-deviation-overload-floor)))

;; Damage reduction handler (called from damage event system)
(defn reduce-damage
  "Reduce incoming damage when VecDeviation is active.
  Returns reduced damage amount."
  [player-id original-damage]
  (try
    (if (fx-common/get-player-state player-id)
      (if (> (double original-damage) (cfg-double :combat.damage-ignore-threshold))
        original-damage
          (let [exp (skill-exp player-id)
            reduction-rate (cfg-lerp :combat.damage-reduction exp)
            max-consumption (cfg-lerp :cost.damage.cp exp)
              current-cp (current-cp player-id)
              consumption (min current-cp (double max-consumption))]
          (when (pos? consumption)
            (consume-cp! player-id consumption))

          (add-exp! player-id (* original-damage (cfg-double :progression.exp-damage-scale)))

          (when-let [pos (get-player-position player-id)]
            (when-let [ctx-id (active-vec-deviation-ctx-id player-id)]
              (send-fx-play! ctx-id pos)))

          (* original-damage (- 1.0 reduction-rate))))
      original-damage)
    (catch Exception e
      (log/warn "VecDeviation reduce-damage failed:" (ex-message e))
      original-damage)))

(defskill vec-deviation
  :id :vec-deviation
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_deviation"
  :description-key "ability.skill.vecmanip.vec_deviation.desc"
  :icon "textures/abilities/vecmanip/skills/vec_deviation.png"
  :ui-position [145 53]
  :level 2
  :controllable? true
  :ctrl-id :vec-deviation
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :pattern :toggle
  :cooldown {:mode :manual}
  :cost {:tick {:cp vec-deviation-cost-tick-cp}}
  :actions {:activate! vec-deviation-activate!
            :deactivate! vec-deviation-deactivate!
            :tick! vec-deviation-tick!
            :abort! vec-deviation-abort!}
  :fx {:start {:topic :vec-deviation/fx-start :payload (fn [_] {})}
       :end {:topic :vec-deviation/fx-end :payload (fn [_] {})}}
  :prerequisites [{:skill-id :vec-accel :min-exp 0.0}])

(defn init!
  []
  (damage-handler/register-toggle-damage-handler!
    :vec-deviation-damage
    :vec-deviation
    (fn [player-id _attacker-id damage _damage-source]
      (let [reduced-damage (reduce-damage player-id damage)]
        [reduced-damage {:handler :vec-deviation}]))
    50)
  nil)
