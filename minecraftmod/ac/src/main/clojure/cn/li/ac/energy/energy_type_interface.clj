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

(defn default-energy-type-runtime-state
	[]
	{:types {}
	 :frozen? false})

(defn create-energy-type-runtime
	([]
	 (create-energy-type-runtime {}))
	([{:keys [state*]
		 :or {state* (atom (default-energy-type-runtime-state))}}]
	 {::runtime ::energy-type-runtime
	  :state* state*}))

(def ^:private _energy-type-runtime (delay (create-energy-type-runtime)))

(def ^:dynamic *energy-type-runtime* nil)

(defn- energy-type-runtime?
	[runtime]
	(and (map? runtime)
		 (= ::energy-type-runtime (::runtime runtime))
		 (some? (:state* runtime))))

(defn call-with-energy-type-runtime
	[runtime f]
	(when-not (energy-type-runtime? runtime)
		(throw (ex-info "Expected energy-type runtime"
						{:runtime runtime})))
	(binding [*energy-type-runtime* runtime]
	  (f)))

(defmacro with-energy-type-runtime
	[runtime & body]
	`(call-with-energy-type-runtime ~runtime (fn [] ~@body)))

(defn- current-energy-type-runtime
	[]
	(or *energy-type-runtime*
      @_energy-type-runtime))

(defn- energy-type-state-atom
	[]
	(:state* (current-energy-type-runtime)))

(defn- energy-type-state-snapshot
	[]
	@(energy-type-state-atom))

(defn- update-energy-type-state!
	[f & args]
	(apply swap! (energy-type-state-atom) f args))

(defn- assert-not-frozen!
	[]
	(when (:frozen? (energy-type-state-snapshot))
		(throw (ex-info "Energy type registry is frozen" {}))))

(defn register-energy-type!
	[energy-type]
	(assert-not-frozen!)
	(let [type-id (energy-type-id energy-type)]
		(when-not (keyword? type-id)
			(throw (ex-info "Energy type id must be a keyword" {:type-id type-id})))
		(update-energy-type-state!
				 update :types
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
	(update-energy-type-state! update :types dissoc type-id)
	nil)

(defn freeze-energy-types!
	[]
	(update-energy-type-state! assoc :frozen? true)
	nil)

(defn energy-types-snapshot
	[]
	(energy-type-state-snapshot))

(defn reset-energy-types-for-test!
	[]
	(reset! (energy-type-state-atom) (default-energy-type-runtime-state))
	nil)

(defn get-energy-type
	[type-id]
	(get (:types (energy-type-state-snapshot)) type-id))

(defn list-energy-types
	[]
	(->> (:types (energy-type-state-snapshot)) vals (sort-by energy-type-id) vec))

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
					(vals (:types (energy-type-state-snapshot))))))