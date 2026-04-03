(ns cn.li.ac.block.energy-converter.schema
  "Energy Converter unified field schema - PURE DATA ONLY.

  Schema is organized by MINECRAFT BLOCK CONCEPTS:
  1. NBT-PERSISTED: Fields saved to disk (server-side state)
  2. GUI-CONTAINER: Client-side GUI container atoms
  3. EPHEMERAL-FIELDS: Runtime state, not persisted

  CRITICAL: This file contains PURE DATA ONLY (no function definitions).
  It can be safely imported by both server-side (block.clj) and client-side (gui.clj) code."
  (:require [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.ac.config.nbt-keys :as nbt-keys]
            [cn.li.ac.block.energy-converter.config :as converter-config]))

;; ============================================================================
;; 1. NBT-PERSISTED FIELDS
;; ============================================================================
;; Fields saved to NBT (disk persistence).
;; Used by: block.clj (server-side)

(def nbt-persisted-fields
  [;; Energy storage
   {:key :energy
    :nbt-key (nbt-keys/get-key :energy)
    :type :double
    :default 0.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Current energy stored"}

   ;; Conversion mode: :charge-items, :export-fe, :import-fe
   {:key :mode
    :nbt-key (nbt-keys/get-key :mode)
    :type :string
    :default "charge-items"
    :persist? true
    :gui-sync? true
    :gui-coerce str
    :gui-close-reset "charge-items"
    :doc "Converter operation mode"}

   ;; Inventory slots
   {:key :input-slot
    :nbt-key (nbt-keys/get-key :input-slot)
    :type :itemstack
    :default nil
    :persist? true
    :gui-sync? false
    :doc "Input item slot for charging"}

   {:key :output-slot
    :nbt-key (nbt-keys/get-key :output-slot)
    :type :itemstack
    :default nil
    :persist? true
    :gui-sync? false
    :doc "Output item slot for charged items"}

   ;; Wireless integration
   {:key :wireless-enabled
    :nbt-key (nbt-keys/get-key :wireless-enabled)
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true
    :gui-coerce boolean
    :gui-close-reset false
    :doc "Whether wireless energy transfer is enabled"}

   {:key :wireless-mode
    :nbt-key (nbt-keys/get-key :wireless-mode)
    :type :string
    :default "generator"
    :persist? true
    :gui-sync? true
    :gui-coerce str
    :gui-close-reset "generator"
    :doc "Wireless mode: generator (provide energy) or receiver (receive energy)"}

   {:key :wireless-bandwidth
    :nbt-key (nbt-keys/get-key :wireless-bandwidth)
    :type :double
    :default 1000.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 1000.0
    :doc "Maximum wireless energy transfer rate (IF/tick)"}

   ;; Face-based I/O configuration
   {:key :face-config
    :nbt-key (nbt-keys/get-key :face-config)
    :type :map
    :default {:north "none" :south "none" :east "none" :west "none" :up "none" :down "none"}
    :persist? true
    :gui-sync? true
    :gui-coerce identity
    :gui-close-reset {:north "none" :south "none" :east "none" :west "none" :up "none" :down "none"}
    :doc "Per-face I/O configuration: input, output, or none"}])

;; ============================================================================
;; 2. GUI-CONTAINER FIELDS
;; ============================================================================
;; Fields that exist in GUI container (client-side atoms).
;; Used by: gui.clj (client-side)

(def gui-container-fields
  [;; Synced from server
   {:key :energy
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Current energy (synced from server)"}

   {:key :mode
    :gui-sync? true
    :gui-coerce str
    :gui-close-reset "charge-items"
    :doc "Converter mode (synced from server)"}

   ;; Derived/computed fields
   {:key :max-energy
    :gui-only? true
    :gui-init (fn [s] (double (get s :max-energy (converter-config/max-energy))))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Max energy capacity"}

   {:key :conversion-rate
    :gui-only? true
    :gui-init (fn [s] (double (get s :conversion-rate (converter-config/fe-conversion-rate))))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "FE conversion rate (1 IF = X FE)"}

   {:key :transfer-rate
    :gui-only? true
    :gui-init (fn [s] (double (get s :transfer-rate 0.0)))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Current transfer rate (IF/T)"}

   {:key :wireless-transfer-rate
    :gui-only? true
    :gui-init (fn [s] (double (get s :wireless-transfer-rate 0.0)))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Current wireless transfer rate (IF/T)"}

   {:key :fe-transfer-rate
    :gui-only? true
    :gui-init (fn [s] (double (get s :fe-transfer-rate 0.0)))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Current Forge Energy transfer rate (FE/T)"}

   {:key :eu-transfer-rate
    :gui-only? true
    :gui-init (fn [s] (double (get s :eu-transfer-rate 0.0)))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Current IC2 EU transfer rate (EU/T)"}

   {:key :efficiency
    :gui-only? true
    :gui-init (fn [s] (double (get s :efficiency 100.0)))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 100.0
    :doc "Conversion efficiency percentage"}

   {:key :total-converted
    :gui-only? true
    :gui-init (fn [s] (double (get s :total-converted 0.0)))
    :gui-sync? true
    :gui-coerce double
    :gui-close-reset 0.0
    :doc "Total energy converted (lifetime)"}

   {:key :tab-index
    :gui-only? true
    :gui-init (fn [_] 0)
    :gui-sync? false
    :gui-coerce int
    :gui-close-reset 0
    :doc "Current GUI tab index (client-only)"}

   {:key :sync-ticker
    :gui-only? true
    :gui-init (fn [_] 0)
    :gui-sync? false
    :gui-coerce int
    :gui-close-reset 0
    :doc "Query throttle ticker (client-only)"}])

;; ============================================================================
;; 3. EPHEMERAL-FIELDS (Not Persisted)
;; ============================================================================
;; Fields that exist at runtime but are not saved to NBT.
;; Used by: block.clj (server-side)

(def ephemeral-fields
  [{:key :transfer-rate
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :doc "Current transfer rate (server-side, synced to GUI)"}

   {:key :wireless-transfer-rate
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :doc "Current wireless transfer rate"}

   {:key :fe-transfer-rate
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :doc "Current Forge Energy transfer rate"}

   {:key :eu-transfer-rate
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :doc "Current IC2 EU transfer rate"}

   {:key :efficiency
    :type :double
    :default 100.0
    :persist? false
    :gui-sync? true
    :doc "Conversion efficiency percentage"}

   {:key :total-converted
    :type :double
    :default 0.0
    :persist? true
    :gui-sync? true
    :doc "Total energy converted (lifetime, persisted for statistics)"}

   {:key :tick-counter
    :type :int
    :default 0
    :persist? false
    :gui-sync? false
    :doc "Internal tick counter for update intervals"}])

;; ============================================================================
;; UNIFIED SCHEMA
;; ============================================================================
;; Merge all field groups, deduplicating by :key.

(def unified-converter-schema
  (state-schema/merge-field-definitions
    [nbt-persisted-fields
     gui-container-fields
     ephemeral-fields]))

;; ============================================================================
;; SCHEMA GROUPS (for documentation)
;; ============================================================================

(def schema-groups
  {:nbt-persisted (count nbt-persisted-fields)      ; 4 fields
   :gui-container (count gui-container-fields)      ; 7 fields
   :ephemeral (count ephemeral-fields)              ; 2 fields
   :total-unique (count unified-converter-schema)}) ; 11 unique fields
