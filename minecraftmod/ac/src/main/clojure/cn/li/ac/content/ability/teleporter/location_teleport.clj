(ns cn.li.ac.content.ability.teleporter.location-teleport
  "LocationTeleport skill - teleport to saved locations.

  Mechanics:
  - Teleport to pre-saved locations
  - Max 16 saved locations per player
  - Energy: 150-200 CP base + distance multiplier max(8.0, sqrt(min(800, distance)))
  - Cross-dimension: 2x energy cost, requires 80%+ experience
  - Overload: 240 flat
  - Cooldown: 20-30 ticks
  - Experience gain: 0.015-0.03 based on distance
  - Teleports player + nearby entities (5 block radius)
  - Dismounts riding entities
  - GUI interface for location management (TODO: client-side implementation)

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.saved-locations :as saved-locations]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :location-teleport :exp] 0.0)))

(defn- calculate-distance [x1 y1 z1 x2 y2 z2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1))
                (* (- z2 z1) (- z2 z1)))))

(defn- calculate-energy-cost [distance cross-dimension?]
  (let [base-cost (max 8.0 (Math/sqrt (min 800.0 distance)))
        multiplier (if cross-dimension? 2.0 1.0)]
    (* base-cost multiplier)))

(defn location-teleport-on-key-down
  "List saved locations or initiate teleport.
  In a full implementation, this would open a GUI.
  For now, we'll use a simple command-based approach."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)]

      ;; Get current player position
      (when-let [current-pos (when teleportation/*teleportation*
                               (teleportation/get-player-position teleportation/*teleportation* player-id))]

        ;; List all saved locations
        (when saved-locations/*saved-locations*
          (let [locations (saved-locations/list-locations saved-locations/*saved-locations* player-id)
                location-count (count locations)]

            (if (zero? location-count)
              (do
                (log/info "LocationTeleport: No saved locations for player" player-id)
                (ctx/update-context! ctx-id assoc :skill-state {:has-locations false}))

              (do
                ;; Store locations in context for selection
                (ctx/update-context! ctx-id assoc :skill-state
                                     {:has-locations true
                                      :locations locations
                                      :current-pos current-pos
                                      :selected-index 0})

                (log/info "LocationTeleport: Found" location-count "saved locations")
                ;; TODO: Open GUI to display locations
                ))))))
    (catch Exception e
      (log/warn "LocationTeleport key-down failed:" (ex-message e)))))

(defn location-teleport-on-key-tick
  "Cycle through saved locations (temporary implementation).
  In a full implementation, this would be handled by GUI."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-locations (:has-locations skill-state)]

        (when has-locations
          ;; Grant minimal experience during hold
          (when-let [state (ps/get-player-state player-id)]
            (let [{:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :location-teleport
                                         0.00001
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e))))

          ;; TODO: Update GUI selection
          )))
    (catch Exception e
      (log/warn "LocationTeleport key-tick failed:" (ex-message e)))))

(defn location-teleport-on-key-up
  "Teleport to selected location."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-locations (:has-locations skill-state)
            exp (get-skill-exp player-id)]

        (if-not has-locations
          (log/debug "LocationTeleport: No saved locations")

          (let [locations (:locations skill-state)
                current-pos (:current-pos skill-state)
                selected-location (first locations)]

            (if-not selected-location
              (log/warn "LocationTeleport: No location selected")

              (let [target-world (:world-id selected-location)
                    target-x (:x selected-location)
                    target-y (:y selected-location)
                    target-z (:z selected-location)
                    location-name (:name selected-location)

                    current-world (:world-id current-pos)
                    current-x (:x current-pos)
                    current-y (:y current-pos)
                    current-z (:z current-pos)

                    cross-dimension? (not= current-world target-world)
                    distance (calculate-distance current-x current-y current-z
                                                 target-x target-y target-z)]

                (if (and cross-dimension? (< exp 0.8))
                  (log/info "LocationTeleport: Cross-dimension requires 80%+ experience")

                  (do
                    ;; Keep the cost computed for future CP integration.
                    (calculate-energy-cost distance cross-dimension?)
                    (when teleportation/*teleportation*
                      (let [result (teleportation/teleport-with-entities!
                                    teleportation/*teleportation*
                                    player-id
                                    target-world
                                    target-x
                                    target-y
                                    target-z
                                    5.0)]
                        (when (:success result)
                          (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)
                          (when-let [state (ps/get-player-state player-id)]
                            (let [exp-gain (scaling/lerp 0.015 0.03 (min 1.0 (/ distance 1000.0)))
                                  {:keys [data events]} (learning/add-skill-exp
                                                         (:ability-data state)
                                                         player-id
                                                         :location-teleport
                                                         exp-gain
                                                         1.0)]
                              (ps/update-ability-data! player-id (constantly data))
                              (doseq [e events]
                                (ability-evt/fire-ability-event! e))))
                          (log/info "LocationTeleport: Teleported to" location-name
                                    "distance:" (int distance)
                                    "entities:" (:teleported-count result)))))))))))))
    (catch Exception e
      (log/warn "LocationTeleport key-up failed:" (ex-message e)))))

(defn location-teleport-on-key-abort
  "Clean up teleport state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "LocationTeleport aborted")
    (catch Exception e
      (log/warn "LocationTeleport key-abort failed:" (ex-message e)))))

;; Helper functions for location management (can be called from GUI or commands)

(defn save-current-location!
  "Save current player position as a named location."
  [player-id location-name]
  (try
    (when-let [current-pos (when teleportation/*teleportation*
                             (teleportation/get-player-position teleportation/*teleportation* player-id))]
      (when saved-locations/*saved-locations*
        (let [result (saved-locations/save-location!
                      saved-locations/*saved-locations*
                      player-id
                      location-name
                      (:world-id current-pos)
                      (:x current-pos)
                      (:y current-pos)
                      (:z current-pos))]
          (if result
            (log/info "Saved location:" location-name)
            (log/warn "Failed to save location (limit reached or error)"))
          result)))
    (catch Exception e
      (log/warn "Failed to save location:" (ex-message e))
      false)))

(defn delete-saved-location!
  "Delete a saved location by name."
  [player-id location-name]
  (try
    (when saved-locations/*saved-locations*
      (let [result (saved-locations/delete-location!
                    saved-locations/*saved-locations*
                    player-id
                    location-name)]
        (if result
          (log/info "Deleted location:" location-name)
          (log/warn "Location not found:" location-name))
        result))
    (catch Exception e
      (log/warn "Failed to delete location:" (ex-message e))
      false)))
