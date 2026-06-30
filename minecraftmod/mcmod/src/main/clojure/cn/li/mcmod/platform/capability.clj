(ns cn.li.mcmod.platform.capability
  "Platform-neutral capability declaration and lookup.

  Content code calls declare-capability! to register a capability type and its
  handler factory. Handler factories are resolved at bundle compile time via
  get-handler-factory."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Registry
;; ============================================================================

; Map of keyword → {:java-type Class :handler-factory-fn (fn [be side] handler)}
; Populated by declare-capability! calls from content namespaces at load time.
(def ^:private ^:dynamic *capability-type-registry* {})

(defn capability-type-registry-snapshot
  []
  *capability-type-registry*)

(defn update-capability-type-registry!
  [f & args]
  (apply alter-var-root #'*capability-type-registry* f args)
  nil)

(defn reset-capability-type-registry!
  "Test-only reset of the static capability type registry."
  []
  (alter-var-root #'*capability-type-registry* (constantly {}))
  nil)

(defn get-capability-entry
  "Look up a capability entry by key. Returns nil if not registered."
  [key]
  (get (capability-type-registry-snapshot) key))

(defn get-handler-factory
  "Return the handler-factory-fn for key, or nil."
  [key]
  (:handler-factory-fn (get-capability-entry key)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn declare-capability!
  "Register a capability type with this mod."
  [key java-type handler-factory-fn]
  (when-not (keyword? key)
    (throw (ex-info "declare-capability!: key must be keyword" {:key key})))
  (let [entry {:java-type java-type :handler-factory-fn handler-factory-fn}]
    (when-let [prev (get-capability-entry key)]
      (when (not= prev entry)
        (throw (ex-info "Duplicate capability registration with different entry"
                        {:key key :previous prev :incoming entry}))))
    (update-capability-type-registry! assoc key entry))
  (log/info "Declared capability" key "->" (.getName ^Class java-type))
  nil)

;; ============================================================================
;; Tile Fluid Spec Registry
;; Maps tile-id (string) → {:mod-id string :path string} for fluid capability.
;; Content registers which fluid a tile holds; platform reads at capability setup.
;; ============================================================================

(defonce ^:private tile-fluid-specs* (atom {}))

(defn register-tile-fluid-spec!
  "Register the fluid a tile holds for IFluidHandler capability binding.
  tile-id is the DSL tile id string; mod-id and fluid-path form the ResourceLocation."
  [tile-id fluid-mod-id fluid-path]
  (swap! tile-fluid-specs* assoc tile-id {:mod-id fluid-mod-id :path fluid-path})
  nil)

(defn get-tile-fluid-spec
  "Return {:mod-id ... :path ...} for tile-id, or nil if not registered."
  [tile-id]
  (get @tile-fluid-specs* tile-id))

(defn reset-tile-fluid-specs-for-test!
  []
  (reset! tile-fluid-specs* {})
  nil)

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
    "Check if the capability is present. Returns boolean.")

  (or-else [this default]
    "Return contained value or `default` if not present."))
