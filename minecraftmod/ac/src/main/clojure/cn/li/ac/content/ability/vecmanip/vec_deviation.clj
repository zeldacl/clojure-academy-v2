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
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :vec-deviation :exp] 0.0)))

(defn- get-player-position [player-id]
  "Get player position from teleportation protocol."
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn vec-deviation-on-key-down
  "Activate or deactivate toggle skill."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [is-active? (toggle/is-toggle-active? ctx-data :vec-deviation)]

        (if is-active?
          ;; Deactivate
          (do
            (toggle/remove-toggle! ctx-id :vec-deviation)
            (log/info "VecDeviation: Deactivated"))

          ;; Activate
          (do
            (toggle/activate-toggle! ctx-id :vec-deviation)
            (ctx/update-context! ctx-id assoc-in [:skill-state :vec-deviation-visited] #{})
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
                    visited (get-in ctx-data [:skill-state :vec-deviation-visited] #{})]

                (doseq [entity entities]
                  (let [entity-id (:uuid entity)]
                    (when (and (not= entity-id player-id)
                               (not (contains? visited entity-id)))
                      (when player-motion/*player-motion*
                        (player-motion/set-velocity! player-motion/*player-motion*
                                                     entity-id 0.0 0.0 0.0))
                      (ctx/update-context! ctx-id update-in [:skill-state :vec-deviation-visited] conj entity-id)
                      (let [deflect-cost (scaling/lerp 15.0 12.0 exp)]
                        (ps/update-ability-data! player-id
                                                 #(update-in % [:cp-data :cp] - deflect-cost)))
                      (when-let [state (ps/get-player-state player-id)]
                        (let [{:keys [data events]} (learning/add-skill-exp
                                                     (:ability-data state)
                                                     player-id
                                                     :vec-deviation
                                                     0.001
                                                     1.0)]
                          (ps/update-ability-data! player-id (constantly data))
                          (doseq [e events]
                            (ability-evt/fire-ability-event! e))))
                      (log/debug "VecDeviation: Deflected entity" entity-id))))))))))
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
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-deviation-visited)
    (log/debug "VecDeviation aborted")
    (catch Exception e
      (log/warn "VecDeviation key-abort failed:" (ex-message e)))))

;; Damage reduction handler (called from damage event system)
(defn reduce-damage
  "Reduce incoming damage when VecDeviation is active.
  Returns reduced damage amount."
  [player-id original-damage]
  (try
    (when-let [state (ps/get-player-state player-id)]
      (let [exp (get-skill-exp player-id)
            reduction-rate (scaling/lerp 0.4 0.9 exp)
            consumption (scaling/lerp 15.0 12.0 exp)
            cp-data (get-in state [:ability-data :cp-data])
            current-cp (:cp cp-data 0.0)]

        (if (>= current-cp consumption)
          (do
            ;; Consume CP
            (ps/update-ability-data! player-id
                                    #(update-in % [:cp-data :cp] - consumption))

            ;; Grant experience
            (let [{:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :vec-deviation
                                         (* original-damage 0.0006)
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e)))

            ;; Return reduced damage
            (* original-damage (- 1.0 reduction-rate)))

          ;; Not enough CP - no reduction
          original-damage)))
    (catch Exception e
      (log/warn "VecDeviation reduce-damage failed:" (ex-message e))
      original-damage)))
