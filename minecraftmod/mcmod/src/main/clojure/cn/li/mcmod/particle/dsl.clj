(ns cn.li.mcmod.particle.dsl
	"Particle DSL - declarative particle type definitions"
	(:require [clojure.string :as str]
						[cn.li.mcmod.protocol.core :as registry-core]
						[cn.li.mcmod.util.log :as log]))

(defn create-particle-registry-runtime
	([] (create-particle-registry-runtime {}))
	([{:keys [registry]}]
	 {:cn.li.mcmod.particle.dsl/runtime ::particle-registry-runtime
	  :registry (or registry (registry-core/atom-registry {}))}))

(def ^:dynamic *particle-registry-runtime* nil)

(defonce ^:private installed-particle-registry-runtime
	(create-particle-registry-runtime))

(defn- particle-registry-state []
	(:registry (or *particle-registry-runtime* installed-particle-registry-runtime)))

(defrecord ParticleSpec [id registry-name always-show? properties])

(defn create-particle-spec
	[particle-id options]
	(map->ParticleSpec
		{:id particle-id
		 :registry-name (or (:registry-name options)
												(str/replace particle-id #"-" "_"))
		 :always-show? (boolean (:always-show? options))
		 :properties (or (:properties options) {})}))

(defn register-particle!
	[particle-spec]
	(when-not (string? (:id particle-spec))
		(throw (ex-info "Particle :id must be string" {:particle-spec particle-spec})))
	(log/info "Registering particle:" (:id particle-spec) "->" (:registry-name particle-spec))
	(registry-core/swap-state! (particle-registry-state) #(assoc % (:id particle-spec) particle-spec))
	particle-spec)

(defn get-particle [particle-id] (registry-core/lookup (particle-registry-state) particle-id))
(defn list-particles [] (keys (registry-core/snapshot (particle-registry-state))))

(defmacro defparticle
	[particle-name & options]
	(if (map? particle-name)
		(let [options-map particle-name
					particle-id (:id options-map)]
			(when-not (string? particle-id)
				(throw (ex-info "Map-form defparticle requires string :id" {:form options-map})))
			`(register-particle!
				 (create-particle-spec ~particle-id ~(dissoc options-map :id))))
		(let [options-map (if (and (= 1 (count options)) (map? (first options)))
												(first options)
												(apply hash-map options))
					particle-id (or (:id options-map) (name particle-name))]
			`(def ~particle-name
				 (register-particle!
					 (create-particle-spec ~particle-id ~(dissoc options-map :id)))))))