(ns cn.li.mcmod.gui.slot-registry
  "Slot validator registry for platform GUI slot creation.

   Game/content modules register predicate functions (stack -> boolean)
   for each slot category/type. Platform adapters then use those validators
  to implement vanilla Slot.mayPlace/mayPickup logic."
  (:require [cn.li.mcmod.gui.slot-schema :as slot-schema]))

(defn- default-slot-registry-runtime-state []
  {:slot-validators {}})

(defn create-slot-registry-runtime
  ([] (create-slot-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.gui.slot-registry/runtime ::slot-registry-runtime
    :state* (or state* (atom (default-slot-registry-runtime-state)))}))

(def ^:dynamic *slot-registry-runtime* nil)

(defonce ^:private installed-slot-registry-runtime
  (create-slot-registry-runtime))

(defn- slot-registry-state-atom []
  (:state* (or *slot-registry-runtime* installed-slot-registry-runtime)))

(defn- slot-registry-state-snapshot []
  @(slot-registry-state-atom))

(defn set-slot-validators!
  "Replace all registered slot validators. Intended for tests."
  [validators]
  (swap! (slot-registry-state-atom) assoc :slot-validators (or validators {}))
  nil)

(defn register-slot-validator!
  "Register a validator predicate for a slot type.

   slot-type examples: :energy, :plate, :core, :output"
  [slot-type validator-fn]
  (swap! (slot-registry-state-atom) assoc-in [:slot-validators (keyword slot-type)] validator-fn)
  nil)

(defn get-slot-validator
  "Get the validator predicate fn for a slot type."
  [slot-type]
  (get-in (slot-registry-state-snapshot) [:slot-validators (keyword slot-type)]))

(defn get-slot-count
  "Get tile slot count for schema-id.
   Read-path wrapper around slot-schema to keep GUI callers on one slot gateway."
  [schema-id]
  (slot-schema/tile-slot-count schema-id))

(defn get-slot-type-for-index
  "Get slot type keyword for a given slot index in schema-id."
  [schema-id slot-index]
  (slot-schema/slot-type schema-id slot-index))

