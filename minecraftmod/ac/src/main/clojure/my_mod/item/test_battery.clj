(ns my-mod.item.test-battery
  "Test energy item implementation
  
  A simple battery item for testing the wireless energy system.
  Implements ImagEnergyItem protocol."
  (:require [my-mod.energy.imag-energy-item :as energy-item]
            [my-mod.item.dsl :as item-dsl]
            [my-mod.platform.item :as item]
            [my-mod.platform.nbt :as nbt]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Test Battery Record
;; ============================================================================

(defrecord TestBattery [max-energy bandwidth]
  energy-item/ImagEnergyItem
  (get-max-energy [this] max-energy)
  (get-bandwidth [this] bandwidth))

;; ============================================================================
;; Battery Definitions
;; ============================================================================

(def battery-configs
  ^{:doc "Battery configurations: name -> [max-energy bandwidth]"}
  {:basic    [10000.0  100.0]   ; Basic battery: 10k energy, 100/tick
   :advanced [50000.0  500.0]   ; Advanced battery: 50k energy, 500/tick
   :ultimate [250000.0 2500.0]}) ; Ultimate battery: 250k energy, 2500/tick

(defn create-battery
  "Create a battery with specified configuration"
  [config-key]
  (if-let [[max-energy bandwidth] (get battery-configs config-key)]
    (->TestBattery max-energy bandwidth)
    (throw (IllegalArgumentException. 
             (str "Unknown battery config: " config-key)))))

;; ============================================================================
;; Battery Items using Item DSL
;; ============================================================================
;; TODO: Battery items disabled - missing texture resources
;; Re-enable when textures are available:
;; - basic_battery.png
;; - advanced_battery.png  
;; - ultimate_battery.png

;; ============================================================================
;; Battery Helper Functions
;; ============================================================================

(defn get-battery-config
  "Get battery configuration from ItemStack"
  [item-stack]
  (when item-stack
    (let [item (item/item-get-item item-stack)
          nbt (item/item-get-tag-compound item-stack)
          battery-type (when nbt (nbt/nbt-get-string nbt "batteryType"))]
      (when battery-type
        (create-battery (keyword battery-type))))))

(defn is-battery?
  "Check if ItemStack is a battery"
  [item-stack]
  ;; All battery items currently disabled (missing textures)
  false)

(defn get-battery-energy
  "Get current energy from battery ItemStack"
  [item-stack]
  (when (is-battery? item-stack)
    (let [nbt (item/item-get-tag-compound item-stack)]
      (if nbt
        (nbt/nbt-get-double nbt "energy")
        0.0))))

(defn set-battery-energy!
  "Set energy in battery ItemStack and update durability bar"
  [item-stack energy]
  (when (is-battery? item-stack)
    (let [config (get-battery-config item-stack)
          max-energy (energy-item/get-max-energy config)
          clamped-energy (min max-energy (max 0.0 energy))
          tag (item/item-get-or-create-tag item-stack)]
      ;; Set energy in NBT
      (nbt/nbt-set-double! tag "energy" clamped-energy)
      
      ;; Update durability bar (inverse: empty = full damage)
      (let [max-damage (item/item-get-max-damage item-stack)
            damage-percent (- 1.0 (/ clamped-energy max-energy))
            damage (int (* damage-percent max-damage))]
        (item/item-set-damage! item-stack damage)))))

(defn charge-battery!
  "Charge energy into battery
  Returns: leftover energy that couldn't be charged"
  [item-stack amount ignore-bandwidth]
  (if (is-battery? item-stack)
    (let [config (get-battery-config item-stack)
          current (get-battery-energy item-stack)
          max-energy (energy-item/get-max-energy config)
          bandwidth (energy-item/get-bandwidth config)
          
          ;; Calculate how much can be charged
          space (- max-energy current)
          limit (if ignore-bandwidth Double/MAX_VALUE bandwidth)
          to-charge (min amount space limit)
          leftover (- amount to-charge)]
      
      ;; Apply charge
      (set-battery-energy! item-stack (+ current to-charge))
      leftover)
    amount)) ; Return all if not a battery

(defn pull-from-battery!
  "Pull energy from battery
  Returns: amount actually pulled"
  [item-stack amount ignore-bandwidth]
  (if (is-battery? item-stack)
    (let [config (get-battery-config item-stack)
          current (get-battery-energy item-stack)
          bandwidth (energy-item/get-bandwidth config)
          
          ;; Calculate how much can be pulled
          limit (if ignore-bandwidth Double/MAX_VALUE bandwidth)
          to-pull (min amount current limit)]
      
      ;; Apply pull
      (set-battery-energy! item-stack (- current to-pull))
      to-pull)
    0.0)) ; Return 0 if not a battery

;; ============================================================================
;; Initialization
;; ============================================================================

(defn get-max-battery-energy
  "Get maximum energy capacity of a battery"
  [item-stack]
  (when-let [config (get-battery-config item-stack)]
    (energy-item/get-max-energy config)))

(defn get-battery-bandwidth
  "Get bandwidth of a battery"
  [item-stack]
  (when-let [config (get-battery-config item-stack)]
    (energy-item/get-bandwidth config)))

(defn init-test-batteries! []
  (log/info "Test batteries initialized: basic, advanced, ultimate"))

;; Export for use in energy/operations.clj
(def ^:export is-battery? is-battery?)
(def ^:export get-battery-energy get-battery-energy)
(def ^:export set-battery-energy! set-battery-energy!)
(def ^:export charge-battery! charge-battery!)
(def ^:export pull-from-battery! pull-from-battery!)
