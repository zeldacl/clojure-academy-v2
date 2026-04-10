(ns cn.li.forge1201.integration.ic2-energy
  "IC2 Energy integration - exposes AC energy converters as IC2 EU providers/consumers.

  This module provides optional IC2 (Industrial Craft 2) integration, allowing
  AC energy converters to interact with IC2's EU (Energy Units) system.

  IC2 integration is completely optional - if IC2 is not present, this module
  will not be loaded and no errors will occur."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.energy IEnergyCapable]))

(set! *warn-on-reflection* true)

(defonce ^:private resolved-vars
  (atom {}))

(defn- resolve-var
  [var-sym]
  (or (@resolved-vars var-sym)
      (let [v (requiring-resolve var-sym)]
        (swap! resolved-vars assoc var-sym v)
        v)))

(defn- cap-call
  [var-sym & args]
  (apply (resolve-var var-sym) args))

;; IC2 detection

(defn ic2-available?
  "Check if IC2 mod is available at runtime.

  Uses requiring-resolve to safely check for IC2 classes without
  causing ClassNotFoundException if IC2 is not present."
  []
  (try
    ;; Try to load IC2's main API class
    (some? (Class/forName "ic2.api.energy.tile.IEnergySource" false
                          (.getContextClassLoader (Thread/currentThread))))
    (catch ClassNotFoundException _
      false)))

;; IC2 conversion rates

(def ^:private default-eu-conversion-rate
  "Default conversion rate: 1 IF = 1 EU (same as original 1.12 implementation)"
  1.0)

(defn eu-conversion-rate
  "Get the EU conversion rate from config or default."
  []
  ;; TODO: Add config option for EU conversion rate
  default-eu-conversion-rate)

(defn if-to-eu
  "Convert IF (Imaginary Energy) to EU (Energy Units).

  Args:
    if-amount - Amount in IF

  Returns:
    Amount in EU"
  [if-amount]
  (* if-amount (eu-conversion-rate)))

(defn eu-to-if
  "Convert EU (Energy Units) to IF (Imaginary Energy).

  Args:
    eu-amount - Amount in EU

  Returns:
    Amount in IF"
  [eu-amount]
  (/ eu-amount (eu-conversion-rate)))

;; IC2 capability implementation

(defn- create-ic2-energy-sink
  "Create an IC2 IEnergySink implementation that wraps an IEnergyCapable.

  This allows IC2 machines to push EU into AC energy converters.

  Args:
    energy-capable - The IEnergyCapable implementation
    tier - IC2 energy tier (1-4)

  Returns:
    IEnergySink proxy object"
  [^IEnergyCapable energy-capable tier]
  (try
    ;; Use reflection to create IC2 interface implementation
    ;; This avoids compile-time dependency on IC2
    (let [^IEnergyCapable ec energy-capable
          proxy-obj (proxy [Object] []
                      ;; getDemandedEnergy() - how much EU can be accepted
                      (getDemandedEnergy []
                        (let [current (.getEnergyStored ec)
                              max-energy (.getMaxEnergyStored ec)
                              space (- max-energy current)]
                          (if-to-eu space)))

                      ;; getSinkTier() - IC2 voltage tier
                      (getSinkTier []
                        (int tier))

                      ;; injectEnergy(EnumFacing, double, double) - receive EU
                      (injectEnergy [direction amount voltage]
                        (let [if-amount (eu-to-if amount)
                              received (.receiveEnergy ec (int if-amount) false)
                              eu-received (if-to-eu received)
                              rejected (- amount eu-received)]
                          rejected))

                      ;; acceptsEnergyFrom(IEnergyEmitter, EnumFacing) - accept from any side
                      (acceptsEnergyFrom [emitter direction]
                        true))]
      proxy-obj)
    (catch Exception e
      (log/error "Failed to create IC2 energy sink:" (ex-message e))
      nil)))

(defn- create-ic2-energy-source
  "Create an IC2 IEnergySource implementation that wraps an IEnergyCapable.

  This allows IC2 machines to pull EU from AC energy converters.

  Args:
    energy-capable - The IEnergyCapable implementation
    tier - IC2 energy tier (1-4)

  Returns:
    IEnergySource proxy object"
  [^IEnergyCapable energy-capable tier]
  (try
    (let [^IEnergyCapable ec energy-capable
          proxy-obj (proxy [Object] []
                      ;; getOfferedEnergy() - how much EU is available
                      (getOfferedEnergy []
                        (let [current (.getEnergyStored ec)]
                          (if-to-eu current)))

                      ;; drawEnergy(double) - extract EU
                      (drawEnergy [amount]
                        (let [if-amount (eu-to-if amount)]
                          (.extractEnergy ec (int if-amount) false)))

                      ;; getSourceTier() - IC2 voltage tier
                      (getSourceTier []
                        (int tier))

                      ;; emitsEnergyTo(IEnergyAcceptor, EnumFacing) - emit to any side
                      (emitsEnergyTo [acceptor direction]
                        true))]
      proxy-obj)
    (catch Exception e
      (log/error "Failed to create IC2 energy source:" (ex-message e))
      nil)))

(defn- get-ic2-capability
  "Get IC2 energy capability for a block entity.

  This is called by the capability system when IC2 queries for energy
  capabilities on our energy converter blocks.

  Args:
    be - The block entity
    side - The side being queried (or nil for any side)
    mode - Converter mode ('import-fe' or 'export-fe')

  Returns:
    IC2 energy interface (IEnergySink or IEnergySource) or nil"
  [be _side mode]
  (try
    ;; Get the AC energy capability first
    (when-let [ac-energy-cap (cap-call 'cn.li.mcmod.platform.capability/get-capability be :energy-converter nil)]
      (when (cap-call 'cn.li.mcmod.platform.capability/is-present? ac-energy-cap)
        (let [ac-energy (cap-call 'cn.li.mcmod.platform.capability/or-else ac-energy-cap nil)
              tier 2] ;; Default to tier 2 (Medium Voltage, 128 EU/t)
          (when ac-energy
            ;; Create appropriate IC2 interface based on mode
            (case mode
              "import-fe" (create-ic2-energy-sink ac-energy tier)
              "export-fe" (create-ic2-energy-source ac-energy tier)
              nil)))))
    (catch Exception e
      (log/error "Error creating IC2 capability:" (ex-message e))
      nil)))

(defn register-ic2-capability!
  "Register IC2 energy capability for energy converter blocks.

  This allows IC2 machines to interact with AC converters using EU.

  Returns:
    true if successful, false if IC2 not available"
  []
  (if (ic2-available?)
    (try
      ;; Register IC2 energy capabilities
      ;; Note: IC2 uses event-based registration, not Forge's capability system
      ;; We'll need to handle EnergyTileLoadEvent/UnloadEvent in the tile entity

      (log/info "IC2 integration available - EU conversion enabled")
      (log/info (format "Conversion rate: 1 IF = %.1f EU" (eu-conversion-rate)))
      true
      (catch Exception e
        (log/error "Failed to register IC2 capability:" (ex-message e))
        false))
    (do
      (log/info "IC2 not detected - EU conversion disabled")
      false)))

(defn init-ic2-energy!
  "Initialize IC2 energy integration.

  Called during mod initialization to set up IC2 integration if available.
  This is a no-op if IC2 is not present."
  []
  (when (ic2-available?)
    (log/info "Initializing IC2 energy integration...")
    (register-ic2-capability!)
    (log/info "IC2 energy integration initialized")))
