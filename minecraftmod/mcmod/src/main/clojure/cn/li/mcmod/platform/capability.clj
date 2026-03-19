(ns cn.li.mcmod.platform.capability
  "Platform-neutral Capability declaration registry.

  ac code calls declare-capability! to register a capability type and its
  handler factory. forge/fabric platform implementations bind
  *declare-capability-impl* to perform the platform-specific slot assignment."
  (:require [my-mod.util.log :as log]))

;; ============================================================================
;; Registry
;; ============================================================================

; Map of keyword → {:java-type Class :handler-factory-fn (fn [be side] handler)}
; Populated by declare-capability! calls from ac namespaces at load time.
(defonce capability-type-registry (atom {}))

;; ============================================================================
;; Platform hook
;; ============================================================================

(def ^:dynamic *declare-capability-impl*
  "Platform-specific implementation of capability slot assignment.
  Bound by forge/fabric init to (fn [key java-type] ...).
  forge: calls CapabilitySlots/assign(key).
  nil until a platform sets it – declare-capability! is safe to call before
  the platform binds this var (it registers into capability-type-registry only)."
  nil)

;; ============================================================================
;; Public API
;; ============================================================================

(defn declare-capability!
  "Register a capability type with this mod.

  Parameters:
  - key             keyword  e.g. :wireless-node
  - java-type       Class    the Java interface class for this capability
  - handler-factory (fn [be side] handler)  creates the capability handler
                    for a given ScriptedBlockEntity instance

  Side effects:
  - Stores entry in capability-type-registry
  - If *declare-capability-impl* is already bound (platform init already ran),
    also calls it immediately to perform slot assignment"
  [key java-type handler-factory-fn]
  (when-not (keyword? key)
    (throw (ex-info "declare-capability!: key must be keyword" {:key key})))
  (swap! capability-type-registry assoc key
         {:java-type         java-type
          :handler-factory-fn handler-factory-fn})
  (log/info "Declared capability" key "->" (.getName ^Class java-type))
  (when *declare-capability-impl*
    (*declare-capability-impl* key java-type))
  nil)

(defn get-capability-entry
  "Look up a capability entry by key. Returns nil if not registered."
  [key]
  (get @capability-type-registry key))

(defn get-handler-factory
  "Return the handler-factory-fn for key, or nil."
  [key]
  (:handler-factory-fn (get-capability-entry key)))

;; ============================================================================
;; Capability Access Protocol
;; ============================================================================

(defprotocol ICapabilityProvider
  "Protocol for objects that can provide capabilities (like BlockEntity)."

  (get-capability [this cap side]
    "Get a capability from this provider. Returns LazyOptional or equivalent."))

(defprotocol ILazyOptional
  "Protocol for LazyOptional-like capability wrappers."

  (is-present? [this]
    "Check if the capability is present. Returns boolean."))
