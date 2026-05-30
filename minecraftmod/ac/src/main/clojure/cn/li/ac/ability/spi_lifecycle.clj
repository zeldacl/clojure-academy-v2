(ns cn.li.ac.ability.spi-lifecycle
	"Ability lifecycle SPI.
   
	 This namespace introduces a protocol-based lifecycle layer so ability
	 definitions can declare activate/tick/deactivate hooks without coupling
	 execution logic to the registry or player-state implementation.
   
	 The SPI is intentionally additive: existing event-driven ability code can
	 keep working while new implementations opt into lifecycle handlers."
	(:require [cn.li.ac.ability.runtime-registry :as runtime-registry]
	          [cn.li.mcmod.util.log :as log]))

(defprotocol AbilityLifecycle
	"Lifecycle hooks for AC abilities and categories."
	(on-activate [this player context]
		"Called when an ability becomes active")
	(on-tick [this player context]
		"Called on each ability tick")
	(on-deactivate [this player context]
		"Called when an ability is deactivated")
	(can-execute? [this player context]
		"Return true when the ability may be executed in the provided context"))

(defn default-lifecycle-registry-runtime-state
	[]
	{:lifecycles {}
	 :frozen? false})

(defn create-lifecycle-registry-runtime
	([]  
	 (create-lifecycle-registry-runtime {}))
	([{:keys [state*]
		 :or {state* (atom (default-lifecycle-registry-runtime-state))}}]
	 {::runtime ::lifecycle-registry-runtime
	  :state* state*}))

(def ^:dynamic *lifecycle-registry-runtime* nil)

(defonce ^:private installed-lifecycle-registry-runtime
	(create-lifecycle-registry-runtime))

(defn call-with-lifecycle-registry-runtime
	[runtime f]
	(runtime-registry/assert-runtime!
		runtime
		::lifecycle-registry-runtime
		"Expected lifecycle registry runtime")
	(binding [*lifecycle-registry-runtime* runtime]
		(f)))

(defn- current-lifecycle-registry-runtime
	[]
	(or *lifecycle-registry-runtime*
		installed-lifecycle-registry-runtime))

(defn- lifecycle-registry-state-atom
	[]
	(runtime-registry/state-atom (current-lifecycle-registry-runtime)))

(defn- lifecycle-registry-state-snapshot
	[]
	(runtime-registry/snapshot (current-lifecycle-registry-runtime)))

(defn- update-lifecycle-registry-state!
	[f & args]
	(apply runtime-registry/update-state! (current-lifecycle-registry-runtime) f args))

(defn- assert-registry-open!
	[]
	(runtime-registry/assert-open!
		(current-lifecycle-registry-runtime)
		:frozen?
		"Ability lifecycle registry is frozen"))

(defn lifecycle-registry-snapshot
	[]
	(lifecycle-registry-state-snapshot))

(defn reset-lifecycle-registry-for-test!
	([]  
	 (reset-lifecycle-registry-for-test! {}))
	([{:keys [lifecycles frozen?]
		 :or {lifecycles {} frozen? false}}]
	 (runtime-registry/reset-state!
	 	 (current-lifecycle-registry-runtime)
	 	 {:lifecycles lifecycles
	 	  :frozen? frozen?})
	 nil))

(defn freeze-lifecycle-registry!
	[]
	(runtime-registry/freeze! (current-lifecycle-registry-runtime) :frozen?))

(defn register-lifecycle!
	"Register an AbilityLifecycle implementation for a skill/category id.
   
	 Accepts either a protocol implementation or a plain map with the keys:
	 :on-activate, :on-tick, :on-deactivate, :can-execute?"
	[ability-id lifecycle]
	(when-not (keyword? ability-id)
		(throw (ex-info "Ability lifecycle id must be a keyword" {:ability-id ability-id})))
	(when-not lifecycle
		(throw (ex-info "Ability lifecycle cannot be nil" {:ability-id ability-id})))
	(if-let [existing (get (:lifecycles (lifecycle-registry-state-snapshot)) ability-id)]
		existing
		(do
			(assert-registry-open!)
			(update-lifecycle-registry-state! assoc-in [:lifecycles ability-id] lifecycle)
			(log/debug "Registered ability lifecycle" ability-id)
			lifecycle)))

(defn unregister-lifecycle!
	[ability-id]
	(assert-registry-open!)
	(update-lifecycle-registry-state! update :lifecycles dissoc ability-id)
	nil)

(defn get-lifecycle
	[ability-id]
	(get (:lifecycles (lifecycle-registry-state-snapshot)) ability-id))

(defn lifecycle-registered?
	[ability-id]
	(contains? (:lifecycles (lifecycle-registry-state-snapshot)) ability-id))

(defn- lifecycle-fn
	[ability-id method-key]
	(when-let [lifecycle (get-lifecycle ability-id)]
		(cond
			(satisfies? AbilityLifecycle lifecycle)
			(case method-key
				:on-activate on-activate
				:on-tick on-tick
				:on-deactivate on-deactivate
				:can-execute? can-execute?)

			(map? lifecycle)
			(get lifecycle method-key)

			:else nil)))

(defn trigger-activate!
	[ability-id player context]
	(when-let [f (lifecycle-fn ability-id :on-activate)]
		(f (get-lifecycle ability-id) player context)))

(defn trigger-tick!
	[ability-id player context]
	(when-let [f (lifecycle-fn ability-id :on-tick)]
		(f (get-lifecycle ability-id) player context)))

(defn trigger-deactivate!
	[ability-id player context]
	(when-let [f (lifecycle-fn ability-id :on-deactivate)]
		(f (get-lifecycle ability-id) player context)))

(defn ability-can-execute?
	[ability-id player context]
	(if-let [f (lifecycle-fn ability-id :can-execute?)]
		(boolean (f (get-lifecycle ability-id) player context))
		true))

(defn clear-lifecycles!
	"Clear all registered lifecycles. Useful for tests."
	[]
	(reset-lifecycle-registry-for-test!))