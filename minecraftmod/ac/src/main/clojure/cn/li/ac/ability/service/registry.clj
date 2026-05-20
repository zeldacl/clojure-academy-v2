(ns cn.li.ac.ability.service.registry
	"Canonical AC skill registry service implementation."
	(:require [cn.li.mcmod.util.log :as log]
						[cn.li.ac.ability.registry.category :as category]
						[cn.li.ac.ability.registry.developer-type :as dev-type]
						[cn.li.ac.ability.skill-config :as skill-config]
						[cn.li.ac.ability.registry.learning-cost :as lc]
						[cn.li.ac.ability.registry.skill-schema :as schema]))

(defonce category-registry category/category-registry)
(defonce skill-registry (atom {}))

(defn register-category!
	[{:keys [id] :as spec}]
	{:pre [(keyword? id) (string? (:name-key spec))]}
	(swap! category-registry assoc id spec)
	(log/info "Registered ability category" id)
	spec)

(defn get-category [cat-id]
	(get @category-registry cat-id))

(defn get-all-categories []
	(vals @category-registry))

(defn list-categories []
	(get-all-categories))

(defn category-enabled? [cat-id]
	(boolean (:enabled (get-category cat-id))))

(defn get-prog-incr-rate [cat-id]
	(get-in @category-registry [cat-id :prog-incr-rate] 1.0))

(defn min-developer-type [level]
	(dev-type/min-for-level level))

(def developer-type-order dev-type/order)

(defn developer-type-gte? [a b]
	(dev-type/gte? a b))

(defn learning-cost [level]
	(lc/learning-cost level))

(defn- build-defaults [{:keys [level]}]
	{:controllable?          true
	 :damage-scale           1.0
	 :cp-consume-speed       1.0
	 :overload-consume-speed 1.0
	 :cooldown-ticks         nil
	 :exp-incr-speed         1.0
	 :destroy-blocks?        true
	 :enabled                true
	 :prerequisites          []
	 :conditions             []
	 :developer-type         (dev-type/min-for-level level)
	 :cooldown               {:mode :default}
	 :targeting {}
	 :transitions {}
	 :exp-policy {}
	 :cooldown-policy {}
	 :state {}
	 :ops {}
	 :perform []
	 :aim {}
	 :exp {}})

(defn- resolve-fn-ref [v]
	(cond
		(fn? v) v
		(var? v) (var-get v)
		:else v))

(defn- normalize-op [id op]
	(when-not (and (vector? op)
								 (= 2 (count op))
								 (keyword? (first op))
								 (map? (second op)))
		(throw (ex-info "Each effect op must be [keyword map]"
										{:skill-id id :op op})))
	op)

(defn- normalize-merged [{:keys [id] :as merged}]
	(let [actions* (into {} (map (fn [[k v]] [k (resolve-fn-ref v)])) (or (:actions merged) {}))
				ops*     (into {} (map (fn [[stage ops]] [stage (mapv #(normalize-op id %) (or ops []))]))
											 (or (:ops merged) {}))
				perform* (mapv #(normalize-op id %) (or (:perform merged) []))
				fx*      (into {} (map (fn [[k evt]]
																 [k (if (map? evt)
																			(update evt :payload resolve-fn-ref)
																			evt)]))
											 (or (:fx merged) {}))]
		(-> merged
				(assoc :actions actions*)
				(assoc :ops ops*)
				(assoc :perform perform*)
				(assoc :fx fx*))))

(defn register-skill!
	[{:keys [id category-id level] :as spec}]
	{:pre [(keyword? id) (keyword? category-id) (integer? level)]}
	(schema/validate! spec)
	(let [full (-> (merge (build-defaults spec) spec)
								 normalize-merged)]
		(swap! skill-registry assoc id full)
		(log/info "Registered skill" id "in category" category-id)
		full))

(defn get-skill [skill-id]
	(some-> (get @skill-registry skill-id)
					skill-config/apply-skill-overrides))

(defn get-skills-for-category [cat-id]
	(filter #(= (:category-id %) cat-id) (map skill-config/apply-skill-overrides (vals @skill-registry))))

(defn list-skills []
	(map skill-config/apply-skill-overrides (vals @skill-registry)))

(defn get-controllable-skills-for-category [cat-id]
	(filter #(and (= (:category-id %) cat-id) (:controllable? %))
					(list-skills)))

(defn get-controllable-skills-at-level [cat-id level]
	(filter #(and (= (:category-id %) cat-id)
								(:controllable? %)
								(= (:level %) level))
					(list-skills)))

(defn can-control? [skill-id]
	(when-let [s (get-skill skill-id)]
		(and (:enabled s) (:controllable? s))))

(defn get-skill-full-id [skill-id]
	(when-let [s (get-skill skill-id)]
		(str (name (:category-id s)) "/" (name skill-id))))

(defn get-skill-icon-path [skill-id]
	(get-in @skill-registry [skill-id :icon] ""))

(defn controllable-key [skill-id]
	(when-let [s (get-skill skill-id)]
		[(:category-id s) (or (:ctrl-id s) skill-id)]))

(defn get-skill-by-controllable [category-id ctrl-id]
	(some (fn [[sid base]]
					(let [s (skill-config/apply-skill-overrides base)]
					(when (and (= (:category-id s) category-id)
										(:enabled s)
										(:controllable? s)
										 (= (or (:ctrl-id s) sid) ctrl-id))
						sid)))
				@skill-registry))