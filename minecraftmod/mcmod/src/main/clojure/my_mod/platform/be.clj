(ns my-mod.platform.be
  "Platform-neutral utilities for interacting with ScriptedBlockEntity.

  ac code should use these functions instead of calling Java interop directly.
  Platform implementations must bind *be-capability-slot-fn* during init so
  that get-capability-slot can retrieve Forge Capability objects by key."
  (:require [my-mod.platform.world :as world]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Platform hook
;; ============================================================================

(def ^:dynamic *be-capability-slot-fn*
  "Platform-specific function (fn [key-string] -> Capability-or-nil).
  Bound by forge init to CapabilitySlots/get.
  nil on platforms that don't use Forge Capabilities."
  nil)

;; ============================================================================
;; Block entity access
;; ============================================================================

(defn get-block-entity
  "Get the BlockEntity at world+pos. Returns nil if none."
  [w block-pos]
  (try
    (world/world-get-tile-entity w block-pos)
    (catch Exception e
      (log/warn "get-block-entity failed:" (.getMessage e))
      nil)))

(defn get-custom-state
  "Get the customState Clojure map from a ScriptedBlockEntity.
  Returns nil if be is nil or doesn't have customState."
  [be]
  (when be
    (try
      (.getCustomState be)
      (catch Exception e
        (log/warn "get-custom-state failed:" (.getMessage e))
        nil))))

(defn set-custom-state!
  "Set the customState map on a ScriptedBlockEntity.
  Marks the BE as changed so it will be saved."
  [be state]
  (when be
    (try
      (.setCustomState be state)
      (catch Exception e
        (log/error "set-custom-state! failed:" (.getMessage e))))))

(defn get-capability-slot
  "Return the Forge Capability object for the given logical key string.
  Returns nil on non-Forge platforms or if the key has not been assigned."
  [key-string]
  (when *be-capability-slot-fn*
    (try
      (*be-capability-slot-fn* key-string)
      (catch Exception _
        nil))))

(defn get-capability
  "Retrieve a capability handler from a BlockEntity by logical key.
  Returns the handler object, or nil if the capability is not present."
  [be key-string]
  (when (and be *be-capability-slot-fn*)
    (try
      (let [cap (*be-capability-slot-fn* key-string)]
        (when cap
          (let [lo (.getCapability be cap nil)]
            (when (.isPresent lo)
              (.orElse lo nil)))))
      (catch Exception _
        nil))))
