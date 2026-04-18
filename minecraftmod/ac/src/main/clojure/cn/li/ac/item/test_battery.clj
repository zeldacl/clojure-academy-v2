(ns cn.li.ac.item.test-battery
  "Test energy item implementation
  
  A simple battery item for testing the wireless energy system.
  Implements ImagEnergyItem protocol."
  (:require [cn.li.ac.energy.imag-energy-item :as energy-item]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Test Battery Record
;; ============================================================================

(defrecord TestBattery [max-energy bandwidth]
  energy-item/ImagEnergyItem
  (get-max-energy [_this] max-energy)
  (get-bandwidth [_this] bandwidth))

;; ============================================================================
;; Battery Definitions
;; ============================================================================

(def battery-configs
  ^{:doc "Battery configurations: name -> [max-energy bandwidth]"}
  {:energy-unit        [10000.0 20.0]
   :developer-portable [100000.0 100.0]})

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

(defn- get-battery-type
  [item-stack]
  (when item-stack
    (let [item-obj (item/item-get-item item-stack)
          registry-id (some-> item-obj item/item-get-registry-name str)
          expected-energy-id (str modid/*mod-id* ":energy_unit")
          expected-portable-id (str modid/*mod-id* ":developer_portable")
          nbt-data (item/item-get-tag-compound item-stack)
          nbt-type (when nbt-data (nbt/nbt-get-string nbt-data "batteryType"))]
      (or (when (seq nbt-type) (keyword nbt-type))
          (cond
            (= registry-id expected-energy-id) :energy-unit
            (= registry-id expected-portable-id) :developer-portable
            :else nil)))))

(defn get-battery-config
  "Get battery configuration from ItemStack"
  [item-stack]
  (when-let [battery-type (get-battery-type item-stack)]
    (create-battery battery-type)))

(defn is-battery?
  "Check if ItemStack is a battery"
  [item-stack]
  (boolean (get-battery-config item-stack)))

(defn get-battery-energy
  "Get current energy from battery ItemStack"
  [item-stack]
  (when (is-battery? item-stack)
    (let [nbt (item/item-get-tag-compound item-stack)]
      (if nbt
        (nbt/nbt-get-double nbt "energy")
        0.0))))

(defn set-battery-energy!
  "Set energy in battery ItemStack."
  [item-stack energy]
  (when (is-battery? item-stack)
    (let [config (get-battery-config item-stack)
          max-energy (energy-item/get-max-energy config)
          clamped-energy (min max-energy (max 0.0 energy))
          bandwidth (energy-item/get-bandwidth config)
          battery-type (name (get-battery-type item-stack))
          tag (item/item-get-or-create-tag item-stack)]
      (nbt/nbt-set-double! tag "energy" clamped-energy)
      (nbt/nbt-set-double! tag "maxEnergy" max-energy)
      (nbt/nbt-set-double! tag "bandwidth" bandwidth)
      (nbt/nbt-set-string! tag "batteryType" battery-type))))

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
  (log/info "Energy item adapters initialized: energy-unit, developer-portable"))
