(ns cn.li.ac.content.ability.vecmanip.arbitration
	"Shared projectile arbitration for vecmanip toggle skills.

	Ensures one projectile is handled by at most one vecmanip skill per tick."
	(:require [clojure.string :as str]
						[cn.li.ac.ability.service.dispatcher :as ctx]
						[cn.li.ac.ability.skill-config :as skill-config]
						[cn.li.ac.ability.util.toggle :as toggle]))

(def ^:private arbitration-config-skill-id :vec-reflection)
(def ^:private arbitration-config-field-id :interaction.projectile-arbitration-priority)

(defn create-projectile-arbitration-runtime
	([]
	 (create-projectile-arbitration-runtime {}))
	([{:keys [projectile-locks*]
		 :or {projectile-locks* (atom {:tick -1 :owners {}})}}]
	 {::runtime ::projectile-arbitration-runtime
	  :projectile-locks* projectile-locks*}))

(def ^:dynamic *projectile-arbitration-runtime* nil)

(defn- projectile-arbitration-runtime?
	[runtime]
	(and (map? runtime)
			 (= ::projectile-arbitration-runtime (::runtime runtime))
			 (some? (:projectile-locks* runtime))))

(defn call-with-projectile-arbitration-runtime
	[runtime f]
	(when-not (projectile-arbitration-runtime? runtime)
		(throw (ex-info "Expected projectile arbitration runtime"
						{:value runtime})))
	(binding [*projectile-arbitration-runtime* runtime]
		(f)))

(defmacro with-projectile-arbitration-runtime
	[runtime & body]
	`(call-with-projectile-arbitration-runtime ~runtime (fn [] ~@body)))

(defn install-projectile-arbitration-runtime!
	([]
	 (install-projectile-arbitration-runtime! (create-projectile-arbitration-runtime)))
	([runtime]
	 (when-not (projectile-arbitration-runtime? runtime)
		 (throw (ex-info "Expected projectile arbitration runtime"
						{:value runtime})))
	 (alter-var-root #'*projectile-arbitration-runtime* (constantly runtime))
	 nil))

(defn clear-projectile-arbitration-runtime!
	[]
	(alter-var-root #'*projectile-arbitration-runtime* (constantly nil))
	nil)

(defn- current-projectile-arbitration-runtime
	[]
	*projectile-arbitration-runtime*)

(defn- require-projectile-arbitration-runtime
	[]
	(or (current-projectile-arbitration-runtime)
			(throw (ex-info "Projectile arbitration runtime is not bound"
							{:required 'projectile-arbitration-runtime}))))

(defn- projectile-locks-atom
	[]
	(:projectile-locks* (require-projectile-arbitration-runtime)))

(defn projectile-locks-snapshot
	[]
	@(projectile-locks-atom))

(defn reset-projectile-locks-for-test!
	([]
	 (reset-projectile-locks-for-test! {:tick -1 :owners {}}))
	([snapshot]
	 (reset! (projectile-locks-atom) (or snapshot {:tick -1 :owners {}}))
	 nil))

(defn clear-player-projectile-locks!
	[player-id]
	(swap! (projectile-locks-atom) update :owners
			 (fn [owners]
				 (into {}
						 (remove (fn [[[owner-player-id _projectile-id] _skill-id]]
									 (= owner-player-id player-id)))
						 (or owners {}))))
	nil)

(defn- current-tick []
	(quot (System/currentTimeMillis) 50))

(defn preferred-skill-id
	"Preferred vecmanip handler when vec-reflection and vec-deviation are both active.
	Controlled by internal tunable `interaction.projectile-arbitration-priority`.
	Defaults to :vec-reflection."
	[]
	(let [v (some-> (skill-config/tunable-string-list arbitration-config-skill-id arbitration-config-field-id)
									first
									str
									str/lower-case
									str/trim)]
		(if (contains? #{"deviation-first" "vec-deviation" "deviation"} v)
			:vec-deviation
			:vec-reflection)))

(defn skill-allowed-in-dual-active?
	"When both vec-reflection and vec-deviation are active, only preferred skill is allowed."
	[skill-id]
	(= skill-id (preferred-skill-id)))

(defn dual-active?
	[player-id]
	(let [contexts (ctx/get-all-contexts)]
		(boolean
			(and (some (fn [[_ctx-id ctx-data]]
									 (and (= (:player-uuid ctx-data) player-id)
												(toggle/is-toggle-active? ctx-data :vec-reflection)))
								 contexts)
					 (some (fn [[_ctx-id ctx-data]]
									 (and (= (:player-uuid ctx-data) player-id)
												(toggle/is-toggle-active? ctx-data :vec-deviation)))
								 contexts)))))

(defn claim-projectile!
	"Try to claim projectile handling for the given player+projectile in current tick.
	Returns true if claim is granted to this skill-id; false otherwise."
	[player-id skill-id projectile-id]
	(if (and player-id projectile-id)
		(let [now-tick (current-tick)
					lock-key [player-id (str projectile-id)]
					state (swap! (projectile-locks-atom)
										 (fn [st]
											 (let [current-state (if (= (:tick st) now-tick)
																							 st
																		 {:tick now-tick :owners {}})
															 owner (get-in current-state [:owners lock-key])]
													 (cond
														 (nil? owner)
														 (assoc-in current-state [:owners lock-key] skill-id)

														 (= owner skill-id)
														 current-state

														 :else
														 current-state))))]
			(= (get-in state [:owners lock-key]) skill-id))
		false))