 (ns cn.li.ac.energy.imag-energy-item
  "ImagEnergyItem protocol - Clojure version of Java interface
  
  Defines the contract for items that can store and transfer energy.
  Used by IFItemManager for energy operations."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; ImagEnergyItem Protocol
;; ============================================================================

(defprotocol ImagEnergyItem
  "Interface for items that can store Imaginary Energy (IF).
  
  Items implementing this protocol can:
  - Store energy up to a maximum capacity
  - Transfer energy with bandwidth limitations"
  
  (get-max-energy [this]
    "Get the maximum energy capacity of this item.
    Returns: double - maximum energy in IF")
  
  (get-bandwidth [this]
    "Get the energy transfer bandwidth (max per operation).
    Returns: double - bandwidth in IF per operation"))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn imag-energy-item? [obj]
  "Check if object satisfies ImagEnergyItem protocol"
  (satisfies? ImagEnergyItem obj))

(defn validate-item-energy
  "Validate energy item parameters"
  [max-energy bandwidth]
  (when (<= max-energy 0.0)
    (throw (IllegalArgumentException. "Max energy must be positive")))
  (when (<= bandwidth 0.0)
    (throw (IllegalArgumentException. "Bandwidth must be positive")))
  true)

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-imag-energy-item! []
  (log/info "ImagEnergyItem protocol initialized"))
