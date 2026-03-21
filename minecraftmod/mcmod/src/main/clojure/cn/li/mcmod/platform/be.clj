(ns cn.li.mcmod.platform.be
  "Platform-neutral utilities for interacting with ScriptedBlockEntity.

  ac code should use these functions instead of calling Java interop directly.
  Platform implementations must bind *be-capability-slot-fn* during init so
  that get-capability-slot can retrieve Forge Capability objects by key."
  (:require [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; BlockEntity Protocol
;; ============================================================================

(defprotocol IBlockEntity
  "Protocol for BlockEntity operations. Platform implementations must extend
  this protocol on the platform BlockEntity class to perform Java interop.
  Core code should call these protocol functions instead of raw interop."

  (be-get-level [this]
    "Get the Level/World from BlockEntity (MC 1.17+ method name)")

  (be-get-world [this]
    "Get the World from BlockEntity (MC 1.16.5 method name)")

  (be-get-custom-state [this]
    "Return the customState map from ScriptedBlockEntity, or nil")

  (be-set-custom-state! [this state]
    "Set the customState map on ScriptedBlockEntity")

  (be-get-block-id [this]
    "Return stable block id identifier string for this BE")

  (be-set-changed! [this]
    "Mark the BE as changed so it will be saved."))

;; Helper function to get world from BlockEntity (tries both methods)
(defn be-get-world-safe
  "Get world from BlockEntity, trying both getLevel() and getWorld()"
  [be]
  (or (try (be-get-level be) (catch Exception _ nil))
      (try (be-get-world be) (catch Exception _ nil))))

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
      (be-get-custom-state be)
      (catch Exception e
        (log/warn "get-custom-state failed:" (.getMessage e))
        nil))))

(defn set-custom-state!
  "Set the customState map on a ScriptedBlockEntity.
  Marks the BE as changed so it will be saved."
  [be state]
  (when be
    (try
      (be-set-custom-state! be state)
      (catch Exception e
        (log/error "set-custom-state! failed:" (.getMessage e))))))

(defn get-block-id
  "Get the block ID string from a ScriptedBlockEntity.
  Returns nil if be is nil or doesn't have getBlockId."
  [be]
  (when be
    (try
      (be-get-block-id be)
      (catch Exception e
        (log/warn "get-block-id failed:" (.getMessage e))
        nil))))

(defn set-changed!
  "Mark a BlockEntity as changed so it will be saved.
  This is a platform-specific operation."
  [be]
  (when be
    (try
      (be-set-changed! be)
      (catch Exception e
        (log/warn "set-changed! failed:" (.getMessage e))
        nil))))

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
          (let [lo (platform-cap/get-capability be cap nil)]
            (when (platform-cap/is-present? lo)
              (platform-cap/or-else lo nil)))))
      (catch Exception _
        nil))))
