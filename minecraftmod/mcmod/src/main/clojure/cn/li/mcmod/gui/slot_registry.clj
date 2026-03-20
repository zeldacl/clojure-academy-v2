(ns cn.li.mcmod.gui.slot-registry
  "Slot validator registry for platform GUI slot creation.

   Game/content modules register predicate functions (stack -> boolean)
   for each slot category/type. Platform adapters then use those validators
   to implement vanilla Slot.mayPlace/mayPickup logic." )

(defonce slot-validators
  ;; Map: slot-type keyword -> validator predicate fn
  (atom {}))

(defn register-slot-validator!
  "Register a validator predicate for a slot type.

   slot-type examples: :energy, :plate, :core, :output"
  [slot-type validator-fn]
  (swap! slot-validators assoc (keyword slot-type) validator-fn)
  nil)

(defn get-slot-validator
  "Get the validator predicate fn for a slot type."
  [slot-type]
  (get @slot-validators (keyword slot-type)))

