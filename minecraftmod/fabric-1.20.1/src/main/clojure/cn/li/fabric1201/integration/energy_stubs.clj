(ns cn.li.fabric1201.integration.energy-stubs
  "Fabric energy integration stubs.

  Fabric doesn't have a native mod energy standard like Forge Energy (RF).
  This namespace provides stubs for energy integration compatibility.

  If Fabric mods need to interact with AC energy converters, they would
  typically use special adapters or their own energy systems. This
  namespace ensures parity with Forge's energy integration interface."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.integration.energy-conversion :as energy-conversion]
            [cn.li.mcmod.platform.energy-integration :as energy-integration]))

;; ============================================================================
;; Fabric Energy Integration (Stub)
;; ============================================================================

(defn init-fabric-energy!
  "Initialize Fabric energy integration.

  Fabric doesn't have a standardized external energy API like Forge.
  This is a stub for future integration if Fabric adopts such a system,
  or for custom integration with Fabric-specific energy mods.

  Returns:
    nil"
  []
  (try
    (log/info "Fabric energy integration initialized (stub - awaiting Fabric energy standard)")
    nil
    (catch Exception e
      (log/error "Failed to initialize Fabric energy integration:" (ex-message e))
      nil)))

(defn register-fabric-energy-capabilities!
  "Register fabric energy capabilities for converter blocks.

  Placeholder for future implementation when Fabric has an energy standard.

  Returns:
    false (not implemented for Fabric)"
  []
  (log/debug "Fabric energy capabilities registration skipped (not yet standardized)")
  false)

;; ============================================================================
;; Conversion Rate Configuration
;; ============================================================================

(defn setup-energy-conversion-rates!
  "Setup energy conversion rates for Fabric.

  This ensures Fabric uses the same conversion rates as Forge for
  consistency when converters are accessed via external APIs.

  Returns:
    nil"
  []
  (try
    ;; Register conversion rate hooks (optional for Fabric, but good for parity)
    (energy-integration/register-energy-integration-hooks!
      {:forge-energy-conversion-rate (fn [] (double energy-conversion/default-fe-to-if-rate))
       :ic2-energy-conversion-rate (fn [] (double energy-conversion/default-eu-to-if-rate))})
    (log/info "Energy conversion rates registered for Fabric compatibility")
    nil
    (catch Exception e
      (log/error "Failed to setup Fabric energy conversion rates:" (ex-message e))
      nil)))
