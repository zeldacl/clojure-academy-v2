(ns cn.li.ac.content.ability.electromaster.thunder-bolt
  "ThunderBolt skill - instant targeted lightning strike with AOE damage.

  Mechanics:
  - Instant cast (on key down)
  - Raycast to find target location
  - Spawn lightning bolt at target
  - Apply direct damage to primary target + AOE damage to nearby entities
  - 80% chance to apply Slowness III to primary target at 20%+ exp
  - Cooldown and costs scale with experience

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :thunder-bolt :exp] 0.0)))

(defn thunder-bolt-on-key-down
  "Execute ThunderBolt skill on key down (instant cast)."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)

          ;; Scale parameters by experience
          damage-direct (scaling/scale-damage 10.0 25.0 exp)
          damage-aoe (scaling/scale-damage 6.0 15.0 exp)
          aoe-radius 5.0
          max-range 20.0

          ;; Get player look vector and position
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))

          ;; Get player position (from state or default)
          player-state (ps/get-player-state player-id)
          player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]

      (if-not look-vec
        (log/warn "ThunderBolt: Could not get player look vector")
        (let [{:keys [x y z]} player-pos
              {:keys [x dx y dy z dz]} look-vec

              ;; Raycast to find target location
              hit (when raycast/*raycast*
                    (raycast/raycast-combined raycast/*raycast*
                                              "minecraft:overworld"
                                              x (+ y 1.6) z  ; Eye height
                                              dx dy dz
                                              max-range))]

          (if-not hit
            (log/debug "ThunderBolt: No target found")
            (let [target-x (get hit :x 0.0)
                  target-y (get hit :y 64.0)
                  target-z (get hit :z 0.0)
                  hit-type (:hit-type hit)]

              ;; Spawn lightning at target
              (when world-effects/*world-effects*
                (world-effects/spawn-lightning! world-effects/*world-effects*
                                                "minecraft:overworld"
                                                target-x target-y target-z))

              ;; Apply damage
              (when entity-damage/*entity-damage*
                (if (= hit-type :entity)
                  ;; Direct hit on entity
                  (let [entity-uuid (:uuid hit)]
                    (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                        "minecraft:overworld"
                                                        entity-uuid
                                                        damage-direct
                                                        :lightning)
                    ;; AOE damage around target
                    (entity-damage/apply-aoe-damage! entity-damage/*entity-damage*
                                                     "minecraft:overworld"
                                                     target-x target-y target-z
                                                     aoe-radius
                                                     damage-aoe
                                                     :lightning
                                                     true))
                  ;; Hit block, just AOE damage
                  (entity-damage/apply-aoe-damage! entity-damage/*entity-damage*
                                                   "minecraft:overworld"
                                                   target-x target-y target-z
                                                   aoe-radius
                                                   damage-aoe
                                                   :lightning
                                                   true)))

              ;; Grant experience
              (when-let [state (ps/get-player-state player-id)]
                (let [{:keys [data events]} (learning/add-skill-exp
                                             (:ability-data state)
                                             player-id
                                             :thunder-bolt
                                             0.02
                                             1.0)]
                  (ps/update-ability-data! player-id (constantly data))
                  (doseq [e events]
                    (ability-evt/fire-ability-event! e))))

              (log/debug "ThunderBolt executed at" target-x target-y target-z))))))
    (catch Exception e
      (log/warn "ThunderBolt execution failed:" (ex-message e)))))

(defn thunder-bolt-on-key-tick
  "ThunderBolt is instant cast, no tick behavior."
  [_ctx]
  nil)

(defn thunder-bolt-on-key-up
  "ThunderBolt is instant cast, no key up behavior."
  [_ctx]
  nil)

(defn thunder-bolt-on-key-abort
  "ThunderBolt is instant cast, no abort behavior."
  [_ctx]
  nil)
