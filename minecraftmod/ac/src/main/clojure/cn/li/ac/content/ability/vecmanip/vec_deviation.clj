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
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private affected-entity-difficulty
  {"minecraft:arrow" 1.0
   "minecraft:potion" 1.4
   "minecraft:snowball" 0.1})

(def ^:private excluded-entity-ids
  #{"minecraft:item"
    "minecraft:xp_bottle"
    "minecraft:experience_bottle"})

(def ^:private large-fireball-ids
  #{"minecraft:fireball" "minecraft:large_fireball"})

(def ^:private small-fireball-ids
  #{"minecraft:small_fireball"})

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :vec-deviation :exp] 0.0)))

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
    (or (contains? excluded-entity-ids eid)
        (:item? entity)
        (:living? entity)
        (:mob? entity))))

(defn- affect-difficulty
  [entity]
  (let [eid (entity-registry-id entity)]
    (when-not (excluded-entity? entity)
      (double (get affected-entity-difficulty eid 1.0)))))

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
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                 (:ability-data state)
                                 player-id
                                 :vec-deviation
                                 amount
                                 1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :vec-deviation/fx-start {:mode :start}))

(defn- send-fx-end! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :vec-deviation/fx-end {:mode :end}))

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

(defn vec-deviation-on-key-down
  "Activate or deactivate toggle skill."
  [{:keys [_player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [is-active? (toggle/is-toggle-active? ctx-data :vec-deviation)]

        (if is-active?
          ;; Deactivate
          (do
            (toggle/remove-toggle! ctx-id :vec-deviation)
            (ctx/update-context! ctx-id update :skill-state dissoc :vec-deviation-visited :vec-deviation-marked)
            (send-fx-end! ctx-id)
            (log/info "VecDeviation: Deactivated"))

          ;; Activate
          (do
            (toggle/activate-toggle! ctx-id :vec-deviation)
            (ctx/update-context! ctx-id assoc-in [:skill-state :vec-deviation-visited] #{})
            (ctx/update-context! ctx-id assoc-in [:skill-state :vec-deviation-marked] #{})
            (send-fx-start! ctx-id)
            (log/info "VecDeviation: Activated")))))
    (catch Exception e
      (log/warn "VecDeviation key-down failed:" (ex-message e)))))

(defn vec-deviation-on-key-tick
  "Tick handler - consume resources and deflect projectiles."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (when (toggle/is-toggle-active? ctx-data :vec-deviation)
        (let [exp (get-skill-exp player-id)
              tick-consumption (scaling/lerp 13.0 5.0 exp)]

          ;; Update toggle tick counter
          (toggle/update-toggle-tick! ctx-id :vec-deviation)

          ;; Consume CP per tick
          (when-let [state (ps/get-player-state player-id)]
            (let [cp-data (get-in state [:ability-data :cp-data])
                  current-cp (:cp cp-data 0.0)]

              (if (>= current-cp tick-consumption)
                ;; Consume CP
                (ps/update-ability-data! player-id
                                        #(update-in % [:cp-data :cp] - tick-consumption))

                ;; Not enough CP - deactivate
                (do
                  (toggle/deactivate-toggle! ctx-id :vec-deviation)
                  (send-fx-end! ctx-id)
                  (log/info "VecDeviation: Deactivated (insufficient CP)")))))

          ;; Find and deflect projectiles around player
          (when-let [pos (get-player-position player-id)]
            (when world-effects/*world-effects*
              (let [world-id (:world-id pos)
                    x (:x pos)
                    y (:y pos)
                    z (:z pos)
                    entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                    world-id x y z 5.0)
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
                               difficulty)
                      (when entity-motion/*entity-motion*
                        (entity-motion/set-velocity! entity-motion/*entity-motion*
                                                     world-id entity-uuid 0.0 0.0 0.0))

                      (when (or (contains? large-fireball-ids eid)
                                (contains? small-fireball-ids eid))
                        (when entity-motion/*entity-motion*
                          (entity-motion/discard-entity! entity-motion/*entity-motion* world-id entity-uuid)))

                      ;; Original special-cases fireballs. We preserve the explosion behavior for large fireballs,
                      ;; while still freezing velocity through platform-neutral entity motion.
                      (when (and (contains? large-fireball-ids eid)
                                 world-effects/*world-effects*)
                        (world-effects/create-explosion! world-effects/*world-effects*
                                                         world-id
                                                         (double (or (:x entity) 0.0))
                                                         (double (or (:y entity) 0.0))
                                                         (double (or (:z entity) 0.0))
                                                         1.0
                                                         false))

                      (let [deflect-cost (* (scaling/lerp 15.0 12.0 exp) difficulty)]
                        (ps/update-ability-data! player-id
                                                 #(update-in % [:cp-data :cp] - deflect-cost)))

                      (add-exp! player-id (* 0.001 difficulty))

                      (let [generic-mark? (and (not (contains? large-fireball-ids eid))
                                               (not (contains? small-fireball-ids eid)))]
                        (when generic-mark?
                          (ctx/update-context! ctx-id update-in [:skill-state :vec-deviation-marked] (fnil conj #{}) entity-uuid))
                        (send-fx-stop-entity! ctx-id entity generic-mark?))

                      (log/debug "VecDeviation: Deflected entity" entity-uuid "difficulty" difficulty))))

                (let [visited-ids (into #{} (keep :uuid entities))]
                  (ctx/update-context! ctx-id update-in [:skill-state :vec-deviation-visited] (fnil into #{}) visited-ids))))))))
    (catch Exception e
      (log/warn "VecDeviation key-tick failed:" (ex-message e)))))

(defn vec-deviation-on-key-up
  "No-op for toggle skills."
  [{:keys [_player-id _ctx-id]}]
  ;; Toggle skills don't use key-up
  nil)

(defn vec-deviation-on-key-abort
  "Deactivate on abort."
  [{:keys [ctx-id]}]
  (try
    (toggle/remove-toggle! ctx-id :vec-deviation)
    (ctx/update-context! ctx-id update :skill-state dissoc :vec-deviation-visited :vec-deviation-marked)
    (send-fx-end! ctx-id)
    (log/debug "VecDeviation aborted")
    (catch Exception e
      (log/warn "VecDeviation key-abort failed:" (ex-message e)))))

;; Damage reduction handler (called from damage event system)
(defn reduce-damage
  "Reduce incoming damage when VecDeviation is active.
  Returns reduced damage amount."
  [player-id original-damage]
  (try
    (if-let [state (ps/get-player-state player-id)]
      (if (> (double original-damage) 9999.0)
        original-damage
        (let [exp (get-skill-exp player-id)
              reduction-rate (scaling/lerp 0.4 0.9 exp)
              max-consumption (scaling/lerp 15.0 12.0 exp)
              cp-data (get-in state [:ability-data :cp-data])
              current-cp (double (:cp cp-data 0.0))
              consumption (min current-cp (double max-consumption))]

          (ps/update-ability-data! player-id
                                   #(update-in % [:cp-data :cp] - consumption))

          (add-exp! player-id (* original-damage 0.0006))

          (when-let [pos (get-player-position player-id)]
            (when-let [ctx-id (active-vec-deviation-ctx-id player-id)]
              (send-fx-play! ctx-id pos)))

          (* original-damage (- 1.0 reduction-rate))))
      original-damage)
    (catch Exception e
      (log/warn "VecDeviation reduce-damage failed:" (ex-message e))
      original-damage)))
