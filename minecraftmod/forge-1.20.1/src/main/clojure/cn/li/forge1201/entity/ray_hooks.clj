(ns cn.li.forge1201.entity.ray-hooks
	"Forge-side registration for scripted ray hook strategies."
	(:require [cn.li.mcmod.util.log :as log]
						[cn.li.mcmod.entity.dsl :as edsl])
	(:import [cn.li.forge1201.bridge ForgeRuntimeBridge]))

(defonce ^:private ray-hooks-installed? (atom false))

(def ^:private default-hook-classes
	{"mine-ray-basic" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "mine-ray-expert" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "mine-ray-luck" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "md-ray" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "md-ray-small" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "md-ray-barrage" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "barrage-ray-pre" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"
	 "railgun-fx" "cn.li.forge1201.entity.ray.hooks.OwnerFollowRayHook"})

(defn- collect-ray-hook-entries
	[]
	(->> (edsl/list-entities)
			 (keep (fn [entity-id]
							 (let [entity-spec (edsl/get-entity entity-id)
										 ray-props (get-in entity-spec [:properties :ray])
										 hook-id (some-> (:hook ray-props) name)
										 hook-class (or (some-> (:hook-class ray-props) str)
																		(get default-hook-classes hook-id))]
								 (when (= :scripted-ray (:entity-kind entity-spec))
									 (cond
										 (or (nil? ray-props) (empty? ray-props))
										 (log/warn "scripted-ray is missing :properties/:ray" {:entity-id entity-id})

										 (or (nil? hook-id) (empty? hook-id))
										 (log/warn "scripted-ray is missing :ray/:hook" {:entity-id entity-id})

										 (or (nil? hook-class) (empty? hook-class))
										 (log/warn "scripted-ray hook has no registered platform hook class"
															 {:entity-id entity-id :hook-id hook-id})

										 :else
										 {:entity-id entity-id
											:hook-id hook-id
											:hook-class hook-class})))))
			 distinct))

(defn- resolve-hook-conflicts
	[hook-entries]
	(->> hook-entries
			 (group-by :hook-id)
			 (keep (fn [[hook-id entries]]
							 (let [classes (distinct (map :hook-class entries))]
								 (if (> (count classes) 1)
									 (do
										 (log/warn "scripted-ray hook-id has conflicting hook-class definitions; skipping registration"
															 {:hook-id hook-id
																:definitions (mapv (fn [{:keys [entity-id hook-class]}]
																										 {:entity-id entity-id
																											:hook-class hook-class})
																									 entries)})
										 nil)
									 [hook-id (first classes)]))))))

(defn register-all-ray-hooks!
	[]
	(when (compare-and-set! ray-hooks-installed? false true)
		(doseq [[hook-id class-name] (-> (collect-ray-hook-entries)
																		 (resolve-hook-conflicts))]
			(if (ForgeRuntimeBridge/registerScriptedRayHookClass hook-id class-name)
				(log/info "Registered scripted ray hook" {:hook-id hook-id :class class-name})
				(log/error "Failed to register scripted ray hook" {:hook-id hook-id :class class-name}))))
	nil)