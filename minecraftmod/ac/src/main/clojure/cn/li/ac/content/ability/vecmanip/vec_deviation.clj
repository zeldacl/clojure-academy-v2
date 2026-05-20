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
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.server.service.skill-effects :as fx-common]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private vec-deviation-skill-id :vec-deviation)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double vec-deviation-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double vec-deviation-skill-id field-id (exp01 exp)))

(defn- cfg-string-set [field-id]
  (set (skill-config/tunable-string-list vec-deviation-skill-id field-id)))

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

(defn- skill-exp [player-id]
  (fx-common/skill-exp player-id vec-deviation-skill-id))

(defn- current-cp
  [player-id]
  (fx-common/current-cp player-id))

(defn- consume-cp!
  [player-id cp]
  (boolean (:success? (fx-common/perform-resource! player-id 0.0 (double cp) false))))

(defn vec-deviation-cost-tick-cp
  [{:keys [player-id]}]
  (cfg-lerp :cost.tick.cp (skill-exp player-id)))

(defn- get-player-position
  "Get player position from teleportation protocol."
  [player-id]
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

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

(defn- add-exp!
  [player-id amount]
  (fx-common/add-skill-exp! player-id :vec-deviation amount))

(defn- send-fx-stop-entity! [ctx-id entity marked?]
  (ctx/ctx-send-to-client! ctx-id :vec-deviation/fx-stop-entity
                           {:mode :stop-entity
                            :x (double (or (:x entity) 0.0))
                            :y (double (or (:y entity) 0.0))
                            :z (double (or (:z entity) 0.0))
                            :marked? (boolean marked?)}))

(defn- send-fx-play! [ctx-id pos]
  (ctx/ctx-send-to-client! ctx-id :vec-deviation/fx-play
                           {:mode :play
                            :x (double (or (:x pos) 0.0))
                            :y (double (or (:y pos) 0.0))
                            :z (double (or (:z pos) 0.0))}))

;; ============================================================================
;; DSL actions (used by :pattern :toggle)
;; ============================================================================

(defn vec-deviation-activate!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :vec-deviation-visited] #{})
  (ctx/update-context! ctx-id assoc-in [:skill-state :vec-deviation-marked] #{})
  (log/info "VecDeviation: Activated"))

(defn vec-deviation-deactivate!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id update :skill-state dissoc :vec-deviation-visited :vec-deviation-marked)
  (log/info "VecDeviation: Deactivated"))

(defn vec-deviation-tick!
  "Tick handler - consume resources and deflect projectiles. Assumes toggle is active."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [exp (skill-exp player-id)
            active? (toggle/is-toggle-active? ctx-data :vec-deviation)]
        (when (and active? (not cost-ok?))
          (toggle/deactivate-toggle! ctx-id :vec-deviation)
          (log/info "VecDeviation: Deactivated (insufficient CP)"))
        (when (and active? cost-ok?)
          (when-let [pos (get-player-position player-id)]
            (when world-effects/*world-effects*
              (let [world-id (:world-id pos)
                    x (:x pos)
                    y (:y pos)
                    z (:z pos)
                    entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                    world-id x y z (cfg-double :targeting.radius))
                    visited (get-in ctx-data [:skill-state :vec-deviation-visited] #{})
                    marked (get-in ctx-data [:skill-state :vec-deviation-marked] #{})
                    fresh-entities (remove (fn [entity]
                                             (contains? visited (:uuid entity)))
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
                               (toggle/is-toggle-active? (or (ctx/get-context ctx-id) ctx-data) :vec-deviation))
                      (let [deflect-cost (* (cfg-lerp :cost.deflect.cp exp) difficulty)]
                        (if-not (consume-cp! player-id deflect-cost)
                          (do
                            (toggle/deactivate-toggle! ctx-id :vec-deviation)
                            (log/info "VecDeviation: Deactivated (insufficient deflect CP)"))
                          (do
                            (when entity-motion/*entity-motion*
                              (entity-motion/set-velocity! entity-motion/*entity-motion*
                                                           world-id entity-uuid 0.0 0.0 0.0))
                            (when (or (contains? (large-fireball-ids) eid)
                                      (contains? (small-fireball-ids) eid))
                              (when entity-motion/*entity-motion*
                                (entity-motion/discard-entity! entity-motion/*entity-motion* world-id entity-uuid)))
                            (when (and (contains? (large-fireball-ids) eid)
                                       world-effects/*world-effects*)
                              (world-effects/create-explosion! world-effects/*world-effects*
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
                                (ctx/update-context! ctx-id update-in [:skill-state :vec-deviation-marked] (fnil conj #{}) entity-uuid))
                              (send-fx-stop-entity! ctx-id entity generic-mark?))
                            (log/debug "VecDeviation: Deflected entity" entity-uuid "difficulty" difficulty))))))
                (let [visited-ids (into #{} (keep :uuid entities))]
                  (ctx/update-context! ctx-id update-in [:skill-state :vec-deviation-visited] (fnil into #{}) visited-ids)))))))))
    (catch Exception e
      (log/warn "VecDeviation tick! failed:" (ex-message e)))))

(defn vec-deviation-abort!
  [{:keys [ctx-id]}]
  (toggle/remove-toggle! ctx-id :vec-deviation)
  (ctx/update-context! ctx-id update :skill-state dissoc :vec-deviation-visited :vec-deviation-marked))

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

(defskill! vec-deviation
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
