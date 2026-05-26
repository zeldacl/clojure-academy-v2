(ns cn.li.ac.energy.energy-type-interface
	"Energy type abstraction and registry.

	This introduces a small, platform-neutral registry so different item energy
	semantics can coexist without hard-coding all callers to one implementation."
	(:require [cn.li.mcmod.util.log :as log]))

(defprotocol EnergyType
	(energy-type-id [this] "Stable keyword identifier for the type")
	(energy-type-name [this] "Display name for debug/UI use")
	(supports-item? [this item-stack] "Return true when this type can operate on the item")
	(get-energy* [this item-stack] "Read current energy")
	(get-capacity* [this item-stack] "Read capacity")
	(get-bandwidth* [this item-stack] "Read transfer rate")
	(set-energy*! [this item-stack amount] "Set exact energy")
	(charge-item*! [this item-stack amount ignore-bandwidth] "Return leftover energy")
	(discharge-item*! [this item-stack amount ignore-bandwidth] "Return extracted energy"))

(defonce ^:private energy-types* (atom {}))
(defonce ^:private energy-types-frozen? (atom false))

(defn- assert-not-frozen!
	[]
	(when @energy-types-frozen?
		(throw (ex-info "Energy type registry is frozen" {}))))

(defn register-energy-type!
	[energy-type]
	(assert-not-frozen!)
	(let [type-id (energy-type-id energy-type)]
		(when-not (keyword? type-id)
			(throw (ex-info "Energy type id must be a keyword" {:type-id type-id})))
		(swap! energy-types*
					 (fn [registry]
						 (if-let [existing (get registry type-id)]
							 (if (= existing energy-type)
								 registry
								 (throw (ex-info "Conflicting energy type id" {:type-id type-id})))
							 (assoc registry type-id energy-type))))
		(log/debug "Registered energy type" type-id)
		energy-type))

(defn unregister-energy-type!
	[type-id]
	(assert-not-frozen!)
	(swap! energy-types* dissoc type-id)
	nil)

(defn freeze-energy-types!
	[]
	(reset! energy-types-frozen? true)
	nil)

(defn energy-types-snapshot
	[]
	{:types @energy-types*
	 :frozen? @energy-types-frozen?})

(defn reset-energy-types-for-test!
	[]
	(reset! energy-types* {})
	(reset! energy-types-frozen? false)
	nil)

(defn get-energy-type
	[type-id]
	(get @energy-types* type-id))

(defn list-energy-types
	[]
	(->> @energy-types* vals (sort-by energy-type-id) vec))

(defn resolve-energy-type
	"Resolve an energy type either by keyword or by probing a candidate item."
	[type-or-item]
	(cond
		(keyword? type-or-item)
		(get-energy-type type-or-item)

		:else
		(some (fn [energy-type]
						(when (supports-item? energy-type type-or-item)
							energy-type))
					(vals @energy-types*))))