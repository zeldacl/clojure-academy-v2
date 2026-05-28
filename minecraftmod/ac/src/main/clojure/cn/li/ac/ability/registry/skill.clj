(ns cn.li.ac.ability.registry.skill
	"Canonical AC skill registry storage and registration API."
	(:require [cn.li.ac.ability.registry.skill-spec :as skill-spec]
					[cn.li.ac.ability.skill-config :as skill-config]
					[cn.li.mcmod.util.log :as log]))

(defn default-skill-registry-runtime-state
	[]
	{:registry {}
	 :frozen? false})

(defn create-skill-registry-runtime
	([]
	 (create-skill-registry-runtime {}))
	([{:keys [state*]
		 :or {state* (atom (default-skill-registry-runtime-state))}}]
	 {::runtime ::skill-registry-runtime
	  :state* state*}))

(def ^:dynamic *skill-registry-runtime* nil)

(defonce ^:private installed-skill-registry-runtime
	(create-skill-registry-runtime))

(defn- skill-registry-runtime?
	[runtime]
	(and (map? runtime)
		 (= ::skill-registry-runtime (::runtime runtime))
		 (some? (:state* runtime))))

(defn call-with-skill-registry-runtime
	[runtime f]
	(when-not (skill-registry-runtime? runtime)
		(throw (ex-info "Expected skill registry runtime"
						{:runtime runtime})))
	(binding [*skill-registry-runtime* runtime]
		(f)))

(defmacro with-skill-registry-runtime
	[runtime & body]
	`(call-with-skill-registry-runtime ~runtime (fn [] ~@body)))

(defn- current-skill-registry-runtime
	[]
	(or *skill-registry-runtime*
		installed-skill-registry-runtime))

(defn- skill-registry-state-atom
	[]
	(:state* (current-skill-registry-runtime)))

(defn- skill-registry-state-snapshot
	[]
	@(skill-registry-state-atom))

(defn- update-skill-registry-state!
	[f & args]
	(apply swap! (skill-registry-state-atom) f args))

(defn- stable-skill-identity
	[spec]
	(select-keys spec [:id :category-id :level :ctrl-id :pattern]))

(defn- assert-registry-open!
	[]
	(when (:frozen? (skill-registry-state-snapshot))
		(throw (ex-info "Skill registry is frozen" {}))))

(defn skill-registry-snapshot
	[]
	(:registry (skill-registry-state-snapshot)))

(defn reset-skill-registry-for-test!
	([]
	 (reset-skill-registry-for-test! {}))
	([snapshot]
	 (reset! (skill-registry-state-atom)
			 {:registry (or snapshot {})
			  :frozen? false})
	 nil))

(defn freeze-skill-registry!
	[]
	(update-skill-registry-state! assoc :frozen? true)
	nil)

(defn register-skill!
	"Validate, normalize, and register a skill spec."
	[{:keys [id category-id level] :as spec}]
	{:pre [(keyword? id) (keyword? category-id) (integer? level)]}
	(let [full (skill-spec/normalize-skill-spec spec)]
		(if-let [existing (get (:registry (skill-registry-state-snapshot)) id)]
			(if (= (stable-skill-identity existing) (stable-skill-identity full))
				existing
				(throw (ex-info "Conflicting skill id"
								{:id id
								 :existing (stable-skill-identity existing)
								 :new (stable-skill-identity full)})))
			(do
				(assert-registry-open!)
				(update-skill-registry-state! assoc-in [:registry id] full)
				(log/info "Registered skill" id "in category" category-id)
				full))))

(defn raw-skill
	"Return the stored skill spec without runtime config overrides."
	[skill-id]
	(get (:registry (skill-registry-state-snapshot)) skill-id))

(defn raw-skills
	"Return stored skill specs without runtime config overrides."
	[]
	(vals (:registry (skill-registry-state-snapshot))))

(defn raw-skill-entries
	"Return [skill-id raw-spec] entries without runtime config overrides."
	[]
	(:registry (skill-registry-state-snapshot)))

(defn get-skill
	"Return the effective skill spec with current skill-config overrides applied."
	[skill-id]
	(some-> (raw-skill skill-id)
				skill-config/apply-skill-overrides))
