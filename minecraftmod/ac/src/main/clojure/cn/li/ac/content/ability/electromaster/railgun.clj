(ns cn.li.ac.content.ability.electromaster.railgun
  "Railgun skill - reflectible beam attack with dual activation modes.

  Mechanics:
  - Dual activation: throw coin for QTE window OR charge with iron ingot/block in hand
  - Energy: 180-120 overload + 200-450 CP + 900-2000 per shot (scales with exp)
  - Damage: 60-110 (scales with exp)
  - Cooldown: 300-160 ticks
  - Reflection: Damage bounces off entities (50% per bounce, max 3 reflections)
  - Grants 0.01 exp on entity hits

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.reflection :as reflection]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :railgun :exp] 0.0)))

(defn railgun-on-key-down
  "Initialize railgun charge or fire immediately."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          max-range (scaling/scale-range 50.0 100.0 exp)

          ;; Get player look vector and position
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))
          player-state (ps/get-player-state player-id)
          player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]

      (if-not look-vec
        (log/warn "Railgun: Could not get player look vector")

        (let [{:keys [x y z]} player-pos
              {:keys [x dx y dy z dz]} look-vec

              ;; Raycast to find target
              hit (when raycast/*raycast*
                    (raycast/raycast-combined raycast/*raycast*
                                              "minecraft:overworld"
                                              x (+ y 1.6) z
                                              dx dy dz
                                              max-range))]

          (if-not hit
            (do
              (log/debug "Railgun: No target found")
              (ctx/update-context! ctx-id assoc :skill-state {:fired false}))

            (let [hit-type (:hit-type hit)]

              ;; Fire railgun immediately on key down
              (if (= hit-type :entity)
                ;; Hit entity - apply reflection damage
                (let [entity-uuid (:uuid hit)
                      base-damage (scaling/scale-damage 60.0 110.0 exp)
                      max-reflections 3

                      ;; Apply reflection damage
                      hit-entities (when entity-damage/*entity-damage*
                                     (entity-damage/apply-reflection-damage!
                                      entity-damage/*entity-damage*
                                      "minecraft:overworld"
                                      entity-uuid
                                      base-damage
                                      :magic
                                      0
                                      max-reflections))]

                  ;; Grant experience based on hits
                  (when (seq hit-entities)
                    (when-let [state (ps/get-player-state player-id)]
                      (let [exp-gain (* 0.01 (count hit-entities))
                            {:keys [data events]} (learning/add-skill-exp
                                                   (:ability-data state)
                                                   player-id
                                                   :railgun
                                                   exp-gain
                                                   1.0)]
                        (ps/update-ability-data! player-id (constantly data))
                        (doseq [e events]
                          (ability-evt/fire-ability-event! e)))))

                  (ctx/update-context! ctx-id assoc :skill-state
                                       {:fired true
                                        :hit-count (count hit-entities)})

                  (log/debug "Railgun fired: hit" (count hit-entities) "entities"))

                ;; Hit block - no damage
                (do
                  (ctx/update-context! ctx-id assoc :skill-state {:fired true :hit-count 0})
                  (log/debug "Railgun fired: hit block"))))))))
    (catch Exception e
      (log/warn "Railgun key-down failed:" (ex-message e)))))

(defn railgun-on-key-tick
  "Railgun is instant cast, no tick behavior needed."
  [{:keys [player-id]}]
  (try
    ;; Grant minimal experience during hold
    (when-let [state (ps/get-player-state player-id)]
      (let [{:keys [data events]} (learning/add-skill-exp
                                   (:ability-data state)
                                   player-id
                                   :railgun
                                   0.0001
                                   1.0)]
        (ps/update-ability-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e))))
    (catch Exception e
      (log/warn "Railgun key-tick failed:" (ex-message e)))))

(defn railgun-on-key-up
  "Grant final experience on key release."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            fired (:fired skill-state)
            hit-count (or (:hit-count skill-state) 0)]

        (when fired
          ;; Grant bonus experience on release
          (when-let [state (ps/get-player-state player-id)]
            (let [exp-gain (if (> hit-count 0) 0.02 0.005)
                  {:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :railgun
                                         exp-gain
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e))))

          (log/debug "Railgun completed"))))
    (catch Exception e
      (log/warn "Railgun key-up failed:" (ex-message e)))))

(defn railgun-on-key-abort
  "Clean up railgun state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "Railgun aborted")
    (catch Exception e
      (log/warn "Railgun key-abort failed:" (ex-message e)))))
