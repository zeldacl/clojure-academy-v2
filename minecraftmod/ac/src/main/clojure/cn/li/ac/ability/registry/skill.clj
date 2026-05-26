(ns cn.li.ac.ability.registry.skill
	"Canonical AC skill registry storage and registration API."
	(:require [cn.li.ac.ability.registry.skill-spec :as skill-spec]
					[cn.li.ac.ability.skill-config :as skill-config]
					[cn.li.mcmod.util.log :as log]))

(defonce ^:private skill-registry (atom {}))
(defonce ^:private skill-registry-frozen? (atom false))

(defn- stable-skill-identity
	[spec]
	(select-keys spec [:id :category-id :level :ctrl-id :pattern]))

(defn- assert-registry-open!
	[]
	(when @skill-registry-frozen?
		(throw (ex-info "Skill registry is frozen" {}))))

(defn skill-registry-snapshot
	[]
	@skill-registry)

(defn reset-skill-registry-for-test!
	([]
	 (reset-skill-registry-for-test! {}))
	([snapshot]
	 (reset! skill-registry (or snapshot {}))
	 (reset! skill-registry-frozen? false)
	 nil))

(defn freeze-skill-registry!
	[]
	(reset! skill-registry-frozen? true)
	nil)

(defn register-skill!
	"Validate, normalize, and register a skill spec."
	[{:keys [id category-id level] :as spec}]
	{:pre [(keyword? id) (keyword? category-id) (integer? level)]}
	(let [full (skill-spec/normalize-skill-spec spec)]
		(if-let [existing (get @skill-registry id)]
			(if (= (stable-skill-identity existing) (stable-skill-identity full))
				existing
				(throw (ex-info "Conflicting skill id"
								{:id id
								 :existing (stable-skill-identity existing)
								 :new (stable-skill-identity full)})))
			(do
				(assert-registry-open!)
				(swap! skill-registry assoc id full)
				(log/info "Registered skill" id "in category" category-id)
				full))))

(defn raw-skill
	"Return the stored skill spec without runtime config overrides."
	[skill-id]
	(get @skill-registry skill-id))

(defn raw-skills
	"Return stored skill specs without runtime config overrides."
	[]
	(vals @skill-registry))

(defn raw-skill-entries
	"Return [skill-id raw-spec] entries without runtime config overrides."
	[]
	@skill-registry)

(defn get-skill
	"Return the effective skill spec with current skill-config overrides applied."
	[skill-id]
	(some-> (raw-skill skill-id)
				skill-config/apply-skill-overrides))
