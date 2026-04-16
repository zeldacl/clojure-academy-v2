(ns cn.li.ac.content.ability.vecmanip.vec-reflection
  "VecReflection skill - advanced reflection (toggle).

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private affected-entity-difficulty
  {"minecraft:arrow" 1.0
   "minecraft:potion" 1.4
   "minecraft:snowball" 0.1})

(def ^:private excluded-entity-ids
  #{"minecraft:item"
    "minecraft:xp_bottle"
    "minecraft:experience_bottle"})

(defonce ^:private reflecting-players (atom #{}))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :vec-reflection :exp] 0.0)))

(defn- get-player-position [player-id]
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn- entity-registry-id [entity]
  (or (:entity-id entity) (:type entity) ""))

(defn- excluded-entity? [entity]
  (let [eid (entity-registry-id entity)]
    (or (contains? excluded-entity-ids eid)
        (:item? entity)
        (:living? entity)
        (:mob? entity))))

(defn- affect-difficulty [entity]
  (let [eid (entity-registry-id entity)]
    (when-not (excluded-entity? entity)
      (double (get affected-entity-difficulty eid 1.0)))))

(defn- active-vec-reflection-ctx-id
  [player-id]
  (->> (ctx/get-all-contexts)
       (filter (fn [[_ctx-id ctx-data]]
                 (and (= (:player-uuid ctx-data) player-id)
                      (toggle/is-toggle-active? ctx-data :vec-reflection))))
       first
       first))

(defn- add-exp! [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                 (:ability-data state)
                                 player-id
                                 :vec-reflection
                                 amount
                                 1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :vec-reflection/fx-start {:mode :start}))

(defn- send-fx-end! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :vec-reflection/fx-end {:mode :end}))

(defn- send-fx-reflect-entity! [ctx-id entity]
  (ctx/ctx-send-to-client! ctx-id :vec-reflection/fx-reflect-entity
                           {:mode :reflect-entity
                            :x (double (or (:x entity) 0.0))
                            :y (double (+ (double (or (:y entity) 0.0))
                                          (* 0.6 (double (or (:eye-height entity)
                                                             (:height entity)
                                                             0.0)))))
                            :z (double (or (:z entity) 0.0))}))

(defn- send-fx-play! [ctx-id pos]
  (ctx/ctx-send-to-client! ctx-id :vec-reflection/fx-play
                           {:mode :play
                            :x (double (or (:x pos) 0.0))
                            :y (double (or (:y pos) 0.0))
                            :z (double (or (:z pos) 0.0))}))

(defn- try-find-attacker-pos [player-id attacker-id]
  (or (when-let [st (ps/get-player-state attacker-id)]
        (get st :position))
      (when-let [self-pos (get-player-position player-id)]
        (when world-effects/*world-effects*
          (first (filter (fn [ent] (= (:uuid ent) attacker-id))
                         (world-effects/find-entities-in-radius
                           world-effects/*world-effects*
                           (:world-id self-pos)
                           (:x self-pos)
                           (:y self-pos)
                           (:z self-pos)
                           20.0)))))))

(defn vec-reflection-on-key-down
  "Activate or deactivate toggle skill."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [is-active? (toggle/is-toggle-active? ctx-data :vec-reflection)
            exp (get-skill-exp player-id)]
        (if is-active?
          (do
            (toggle/remove-toggle! ctx-id :vec-reflection)
            (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited)
            (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-overload-keep)
            (send-fx-end! ctx-id)
            (log/info "VecReflection: Deactivated"))
          (do
            (toggle/activate-toggle! ctx-id :vec-reflection)
            (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-visited] #{})
            (let [overload-keep (scaling/lerp 350.0 250.0 exp)]
              (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-overload-keep] overload-keep)
              (when (ps/get-player-state player-id)
                (ps/update-ability-data! player-id
                                         #(update-in % [:cp-data :overload] + overload-keep))))
            (send-fx-start! ctx-id)
            (log/info "VecReflection: Activated")))))
    (catch Exception e
      (log/warn "VecReflection key-down failed:" (ex-message e)))))

(defn vec-reflection-on-key-tick
  "Tick handler - consume resources and reflect nearby projectiles."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (when (toggle/is-toggle-active? ctx-data :vec-reflection)
        (let [exp (get-skill-exp player-id)
              tick-consumption (scaling/lerp 15.0 11.0 exp)
              overload-keep (get-in ctx-data [:skill-state :vec-reflection-overload-keep] 0.0)]
          (toggle/update-toggle-tick! ctx-id :vec-reflection)

          (when-let [state (ps/get-player-state player-id)]
            (let [cp-data (get-in state [:ability-data :cp-data])
                  current-overload (:overload cp-data 0.0)]
              (when (< current-overload overload-keep)
                (ps/update-ability-data! player-id
                                         #(assoc-in % [:cp-data :overload] overload-keep)))))

          (when-let [state (ps/get-player-state player-id)]
            (let [cp-data (get-in state [:ability-data :cp-data])
                  current-cp (:cp cp-data 0.0)]
              (if (>= current-cp tick-consumption)
                (ps/update-ability-data! player-id
                                         #(update-in % [:cp-data :cp] - tick-consumption))
                (do
                  (toggle/deactivate-toggle! ctx-id :vec-reflection)
                  (send-fx-end! ctx-id)
                  (log/info "VecReflection: Deactivated (insufficient CP)")))))

          (when-let [pos (get-player-position player-id)]
            (when world-effects/*world-effects*
              (let [world-id (:world-id pos)
                    x (:x pos)
                    y (:y pos)
                    z (:z pos)
                    entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                    world-id x y z 4.0)
                    visited (get-in ctx-data [:skill-state :vec-reflection-visited] #{})
                    fresh-entities (remove (fn [entity]
                                             (contains? visited (:uuid entity)))
                                           entities)]
                (doseq [entity fresh-entities]
                  (let [entity-id (:uuid entity)
                        difficulty (affect-difficulty entity)]
                    (when (and entity-id
                               (not= entity-id player-id)
                               difficulty)
                      (when-let [look-vec (and raycast/*raycast*
                                               (raycast/get-player-look-vector raycast/*raycast* player-id))]
                        (let [entity-vel (when entity-motion/*entity-motion*
                                           (entity-motion/get-velocity entity-motion/*entity-motion*
                                                                       world-id
                                                                       entity-id))
                              speed (Math/sqrt (+ (Math/pow (double (or (:x entity-vel) 0.0)) 2.0)
                                                  (Math/pow (double (or (:y entity-vel) 0.0)) 2.0)
                                                  (Math/pow (double (or (:z entity-vel) 0.0)) 2.0)))
                              vel-x (* (double (:x look-vec)) speed)
                              vel-y (* (double (:y look-vec)) speed)
                              vel-z (* (double (:z look-vec)) speed)]
                          (when entity-motion/*entity-motion*
                            (entity-motion/set-velocity! entity-motion/*entity-motion*
                                                         world-id
                                                         entity-id vel-x vel-y vel-z))
                          (let [reflect-cost (* difficulty (scaling/lerp 300.0 160.0 exp))]
                            (ps/update-ability-data! player-id
                                                     #(update-in % [:cp-data :cp] - reflect-cost)))
                          (add-exp! player-id (* difficulty 0.0008))
                          (send-fx-reflect-entity! ctx-id entity)
                          (log/debug "VecReflection: Reflected entity" entity-id))))))
                (let [visited-ids (into #{} (keep :uuid entities))]
                  (ctx/update-context! ctx-id update-in [:skill-state :vec-reflection-visited] (fnil into #{}) visited-ids))))))))
    (catch Exception e
      (log/warn "VecReflection key-tick failed:" (ex-message e)))))

(defn vec-reflection-on-key-up
  "No-op for toggle skills."
  [{:keys [_player-id _ctx-id]}]
  nil)

(defn vec-reflection-on-key-abort
  "Deactivate on abort."
  [{:keys [ctx-id]}]
  (try
    (toggle/remove-toggle! ctx-id :vec-reflection)
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited)
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-overload-keep)
    (send-fx-end! ctx-id)
    (log/debug "VecReflection aborted")
    (catch Exception e
      (log/warn "VecReflection key-abort failed:" (ex-message e)))))

(defn reflect-damage
  "Reflect incoming damage back to attacker when VecReflection is active.
  Returns tuple [performed? reduced-damage]."
  [player-id attacker-id original-damage]
  (try
    (if (contains? @reflecting-players player-id)
      [false original-damage]
      (do
        (swap! reflecting-players conj player-id)
        (try
          (if-let [state (ps/get-player-state player-id)]
            (let [exp (get-skill-exp player-id)
                  reflect-multiplier (scaling/lerp 0.6 1.2 exp)
                  reflected-damage (* original-damage reflect-multiplier)
                  consumption (* original-damage (scaling/lerp 20.0 15.0 exp))
                  cp-data (get-in state [:ability-data :cp-data])
                  current-cp (:cp cp-data 0.0)]
              (if (and (>= current-cp consumption)
                       (>= reflected-damage 1.0))
                (do
                  (ps/update-ability-data! player-id
                                           #(update-in % [:cp-data :cp] - consumption))
                  (when (and attacker-id entity-damage/*entity-damage*)
                    (let [world-id (or (get-in state [:position :world-id])
                                       (get-in (ps/get-player-state attacker-id) [:position :world-id])
                                       "minecraft:overworld")]
                      (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                         world-id
                                                         attacker-id
                                                         reflected-damage
                                                         :generic)))
                  (add-exp! player-id (* original-damage 0.0004))
                  (when-let [ctx-id (active-vec-reflection-ctx-id player-id)]
                    (when-let [attacker-pos (and attacker-id (try-find-attacker-pos player-id attacker-id))]
                      (send-fx-play! ctx-id attacker-pos)))
                  [true (max 0.0 (- original-damage reflected-damage))])
                [false original-damage]))
            [false original-damage])
          (finally
            (swap! reflecting-players disj player-id)))))
    (catch Exception e
      (log/warn "VecReflection reflect-damage failed:" (ex-message e))
      [false original-damage])))

(defn can-cancel-attack?
  "Pure precheck for Attack-stage cancel semantics.
  Mirrors original passby gate: only cancels when reflection can actually perform."
  [player-id _attacker-id original-damage]
  (try
    (if-let [state (ps/get-player-state player-id)]
      (let [ctx-id (active-vec-reflection-ctx-id player-id)
            exp (get-skill-exp player-id)
            consumption (* original-damage (scaling/lerp 20.0 15.0 exp))
            reflected-damage (* original-damage (scaling/lerp 0.6 1.2 exp))
            current-cp (double (get-in state [:ability-data :cp-data :cp] 0.0))]
        (and ctx-id
             (>= current-cp consumption)
             (>= reflected-damage 1.0)))
      false)
    (catch Exception e
      (log/warn "VecReflection can-cancel-attack failed:" (ex-message e))
      false)))
