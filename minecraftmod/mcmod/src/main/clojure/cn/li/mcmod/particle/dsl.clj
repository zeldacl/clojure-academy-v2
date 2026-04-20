(ns cn.li.mcmod.particle.dsl
	"Particle DSL - declarative particle type definitions"
	(:require [clojure.string :as str]
						[cn.li.mcmod.util.log :as log]))

(defonce particle-registry (atom {}))

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
	(swap! particle-registry assoc (:id particle-spec) particle-spec)
	particle-spec)

(defn get-particle [particle-id] (get @particle-registry particle-id))
(defn list-particles [] (keys @particle-registry))

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