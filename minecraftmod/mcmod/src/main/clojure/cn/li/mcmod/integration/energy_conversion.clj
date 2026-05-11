(ns cn.li.mcmod.integration.energy-conversion
  "Platform-neutral energy conversion definitions.

  This namespace defines conversion formulas between different energy systems:
  - IF (Imaginary Energy) - AcademyCraft's internal energy unit
  - FE (Forge Energy) - Forge mod's energy standard
  - EU (Energy Units) - Industrial Craft 2's energy standard

  Conversion rates are configuration-based and platform-agnostic.
  No Minecraft or platform-specific imports allowed.

  These constants and functions are used by both Forge and Fabric
  platform layers to implement energy adapter logic.")

;; ============================================================================
;; Conversion Rate Configuration
;; ============================================================================

(def ^:const default-fe-to-if-rate
  "Default Forge Energy to Imaginary Energy conversion rate.
  
  1 FE (Forge Energy) = X IF (Imaginary Energy)
  
  Historical note: Minecraft 1.20.1 uses 1 IF = 4 FE as default.
  This constant defines the reverse: FE per 1 IF.
  
  Default: 4.0 (1 IF = 4 FE; equivalently, 1 FE = 0.25 IF)"
  4.0)

(def ^:const default-eu-to-if-rate
  "Default IC2 Energy Unit to Imaginary Energy conversion rate.
  
  1 EU (Energy Unit) = X IF (Imaginary Energy)
  
  Historical note: Early AcademyCraft versions used 1 IF = 1 EU.
  This constant defines the rate: EU per 1 IF.
  
  Default: 1.0 (1 IF = 1 EU)"
  1.0)

;; ============================================================================
;; Conversion Functions (Pure Arithmetic)
;; ============================================================================

(defn if-to-fe
  "Convert Imaginary Energy to Forge Energy.
  
  Args:
    if-amount: Amount in IF (Imaginary Energy)
    fe-rate: Conversion rate (FE per 1 IF) - use default-fe-to-if-rate if nil
  
  Returns:
    Amount in FE (Forge Energy), as integer"
  [if-amount & {:keys [rate] :or {rate default-fe-to-if-rate}}]
  (int (* (double if-amount) (double rate))))

(defn fe-to-if
  "Convert Forge Energy to Imaginary Energy.
  
  Args:
    fe-amount: Amount in FE (Forge Energy)
    fe-rate: Conversion rate (FE per 1 IF) - use default-fe-to-if-rate if nil
  
  Returns:
    Amount in IF (Imaginary Energy), as integer"
  [fe-amount & {:keys [rate] :or {rate default-fe-to-if-rate}}]
  (int (/ (double fe-amount) (double rate))))

(defn if-to-eu
  "Convert Imaginary Energy to IC2 Energy Units.
  
  Args:
    if-amount: Amount in IF (Imaginary Energy)
    eu-rate: Conversion rate (EU per 1 IF) - use default-eu-to-if-rate if nil
  
  Returns:
    Amount in EU (Energy Units), as double"
  [if-amount & {:keys [rate] :or {rate default-eu-to-if-rate}}]
  (* (double if-amount) (double rate)))

(defn eu-to-if
  "Convert IC2 Energy Units to Imaginary Energy.
  
  Args:
    eu-amount: Amount in EU (Energy Units)
    eu-rate: Conversion rate (EU per 1 IF) - use default-eu-to-if-rate if nil
  
  Returns:
    Amount in IF (Imaginary Energy), as double"
  [eu-amount & {:keys [rate] :or {rate default-eu-to-if-rate}}]
  (/ (double eu-amount) (double rate)))

;; ============================================================================
;; Rate Validation (Pure Validation)
;; ============================================================================

(defn validate-conversion-rate
  "Validate a conversion rate value.
  
  Args:
    rate: The rate to validate
  
  Returns:
    true if valid (positive number), false otherwise"
  [rate]
  (and (number? rate) (pos? rate)))

(defn clamp-energy
  "Clamp an energy value to a valid range.
  
  Args:
    energy: Current energy
    min-energy: Minimum allowed (inclusive)
    max-energy: Maximum allowed (inclusive)
  
  Returns:
    Energy clamped to [min-energy, max-energy]"
  [energy min-energy max-energy]
  (let [e (double energy)
        min-e (double min-energy)
        max-e (double max-energy)]
    (max min-e (min e max-e))))

;; ============================================================================
;; Rate Calculation Helpers
;; ============================================================================

(defn calculate-transfer-amount
  "Calculate how much energy can be transferred given source and target constraints.
  
  This is used by adapters to determine transfer amounts that satisfy
  both source and destination constraints.
  
  Args:
    requested: Amount requested to transfer
    source-available: Amount available in source
    target-space: Amount of space in target
    rate-multiplier: Conversion multiplier (default 1.0 for same-unit transfers)
  
  Returns:
    Amount that can safely be transferred, as integer"
  [requested source-available target-space & {:keys [rate-multiplier] :or {rate-multiplier 1.0}}]
  (int (min (double requested)
            (double source-available)
            (/ (double target-space) (double rate-multiplier)))))
