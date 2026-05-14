(ns cn.li.mc1201.runtime.accessor-registry-world
  "World-domain accessor registrations.
   
   Provides platform-independent accessors for world-related operations:
   - Block queries and manipulation
   - World state access
   - Raycast operations
   - Saved locations"
  (:require [cn.li.mc1201.runtime.accessor-registry-core :as core]))

(defonce register-world-accessors!
  (fn []
    ;; Block query accessors
    (core/register-accessor! :world :get-block-type
      (fn [_ _] nil)
      "Get block type at position. Returns block identifier keyword.")

    (core/register-accessor! :world :get-block-metadata
      (fn [_ _] nil)
      "Get block metadata/properties at position. Returns map of properties.")

    (core/register-accessor! :world :is-block-air?
      (fn [_ _] nil)
      "Check if position contains air block. Returns boolean.")

    (core/register-accessor! :world :is-replaceable?
      (fn [_ _] nil)
      "Check if block at position is replaceable. Returns boolean.")

    ;; Block manipulation
    (core/register-accessor! :world :set-block!
      (fn [_ _ _ _] nil)
      "Set block at position. Parameters: block-id (keyword), properties (map)")

    (core/register-accessor! :world :replace-block!
      (fn [_ _ _ _] nil)
      "Replace block if it matches old-block specification.")

    (core/register-accessor! :world :break-block!
      (fn [_ _ _] nil)
      "Break block at position. Returns success boolean.")

    ;; World effects
    (core/register-accessor! :world :particle-effect
      (fn [_ _ _ _] nil)
      "Spawn particle effect at position. Parameters: effect-type (keyword), velocity (map)")

    (core/register-accessor! :world :sound-effect
      (fn [_ _ _ _ _] nil)
      "Play sound at position. Parameters: volume (0-1), pitch (float)")

    (core/register-accessor! :world :explosion
      (fn [_ _ _ _ _] nil)
      "Create explosion at position. Parameters: radius (float)")

    ;; Raycast operations
    (core/register-accessor! :world :raycast-block
      (fn [_ _ _ _] nil)
      "Cast ray for block collision. Returns {:block block-pos :hit-pos vector}")

    (core/register-accessor! :world :raycast-entity
      (fn [_ _ _ _ _] nil)
      "Cast ray for entity collision. Returns matching entities.")

    ;; Saved locations
    (core/register-accessor! :world :save-location
      (fn [_ _ _] nil)
      "Save a world location with identifier.")

    (core/register-accessor! :world :load-location
      (fn [_ _] nil)
      "Load saved location by identifier. Returns world-pos or nil.")

    (core/register-accessor! :world :list-saved-locations
      (fn [_] nil)
      "List all saved location identifiers in this world.")

    (core/register-accessor! :world :delete-location
      (fn [_ _] nil)
      "Delete saved location by identifier.")

    nil))

(def init-world-accessors
  (try
    (register-world-accessors!)
    true
    (catch Exception e
      (throw (ex-info "Failed to register world accessors" {} e)))))
