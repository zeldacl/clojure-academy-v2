(ns cn.li.mc1201.runtime.accessor-registry-entity
  "Entity-domain accessor registrations.
   
   Provides platform-independent accessors for entity-related operations:
   - Entity position and motion
   - Player-specific operations
   - Damage and effects
   - Teleportation"
  (:require [cn.li.mc1201.runtime.accessor-registry-core :as core]))

(defn- register-entity-accessors-impl!
  []
    ;; Position & Motion accessors
    (core/register-accessor! :entity :get-entity-pos
      (fn [_] nil)
      "Get entity position. Returns {:x :y :z} map.")

    (core/register-accessor! :entity :set-entity-pos
      (fn [_ _ _ _] nil)
      "Set entity position. Parameters: x, y, z (doubles)")

    (core/register-accessor! :entity :get-entity-velocity
      (fn [_] nil)
      "Get entity velocity. Returns {:vx :vy :vz} map.")

    (core/register-accessor! :entity :set-entity-velocity
      (fn [_ _ _ _] nil)
      "Set entity velocity. Parameters: vx, vy, vz (doubles)")

    (core/register-accessor! :entity :get-entity-rotation
      (fn [_] nil)
      "Get entity rotation (yaw/pitch). Returns {:yaw :pitch} map.")

    (core/register-accessor! :entity :set-entity-rotation
      (fn [_ _ _] nil)
      "Set entity rotation. Parameters: yaw, pitch (floats)")

    ;; Movement & lifecycle
    (core/register-accessor! :entity :teleport-entity!
      (fn [_ _ _ _] nil)
      "Teleport entity to position. Returns true when successful.")

    (core/register-accessor! :entity :kill-entity!
      (fn [_] nil)
      "Kill/remove entity. Returns true when successful.")

    (core/register-accessor! :entity :damage-entity!
      (fn [_ _] nil)
      "Apply damage to entity. Returns remaining health or true.")

    (core/register-accessor! :entity :heal-entity!
      (fn [_ _] nil)
      "Heal entity by amount. Returns updated health or true.")

    (core/register-accessor! :entity :apply-status-effect!
      (fn [_ _ _ _] nil)
      "Apply status effect to entity. Returns true when successful.")

    ;; Player-specific operations
    (core/register-accessor! :entity :get-player-name
      (fn [_] nil)
      "Get player display name. Returns string.")

    (core/register-accessor! :entity :is-player-sneaking?
      (fn [_] nil)
      "Check if player is sneaking. Returns boolean.")

    (core/register-accessor! :entity :is-player-sprinting?
      (fn [_] nil)
      "Check if player is sprinting. Returns boolean.")

    (core/register-accessor! :entity :get-player-gamemode
      (fn [_] nil)
      "Get player gamemode. Returns keyword or enum value.")

    ;; Damage & status
    (core/register-accessor! :entity :get-entity-health
      (fn [_] nil)
      "Get entity health. Returns numeric health value.")

    (core/register-accessor! :entity :set-entity-health
      (fn [_ _] nil)
      "Set entity health to exact value. Returns true when successful.")

    (core/register-accessor! :entity :get-active-effects
      (fn [_] nil)
      "Get all active status effects. Returns collection of effect maps.")

    nil)

(defonce ^:private entity-accessors-registered?
  (atom false))

(defn register-entity-accessors!
  "Explicitly register entity-domain accessors once."
  []
  (when (compare-and-set! entity-accessors-registered? false true)
    (try
      (register-entity-accessors-impl!)
      (catch Throwable t
        (reset! entity-accessors-registered? false)
        (throw (ex-info "Failed to register entity accessors" {} t)))))
  nil)
