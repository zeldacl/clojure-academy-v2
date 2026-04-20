(ns cn.li.mcmod.sound.dsl
	"Sound DSL - declarative sound event definitions"
	(:require [clojure.string :as str]
						[cn.li.mcmod.util.log :as log]))

(defonce sound-registry (atom {}))

(defrecord SoundSpec [id registry-name subtitle-key properties])

(defn create-sound-spec
	"Create a sound specification from options."
	[sound-id options]
	(map->SoundSpec
		{:id sound-id
		 :registry-name (or (:registry-name options)
												(str/replace sound-id #"-" "_"))
		 :subtitle-key (:subtitle-key options)
		 :properties (or (:properties options) {})}))

(defn validate-sound-spec
	[sound-spec]
	(when-not (:id sound-spec)
		(throw (ex-info "Sound must have an :id" {:spec sound-spec})))
	(when-not (string? (:id sound-spec))
		(throw (ex-info "Sound :id must be a string" {:id (:id sound-spec)})))
	(when-not (string? (:registry-name sound-spec))
		(throw (ex-info "Sound :registry-name must be a string"
										{:id (:id sound-spec)
										 :registry-name (:registry-name sound-spec)})))
	true)

(defn register-sound!
	[sound-spec]
	(validate-sound-spec sound-spec)
	(log/info "Registering sound:" (:id sound-spec) "->" (:registry-name sound-spec))
	(swap! sound-registry assoc (:id sound-spec) sound-spec)
	sound-spec)

(defn get-sound
	[sound-id]
	(get @sound-registry sound-id))

(defn list-sounds
	[]
	(keys @sound-registry))

(defmacro defsound
	"Define a sound event.

	Supports two forms:
	1) Map form: (defsound {:id \"em.arc_strong\" ...})
	2) Symbol form: (defsound em-arc-strong :id \"em.arc_strong\" ...)
	"
	[sound-name & options]
	(if (map? sound-name)
		(let [options-map sound-name
					sound-id (:id options-map)]
			(when-not (string? sound-id)
				(throw (ex-info "Map-form defsound requires string :id"
												{:form options-map})))
			`(register-sound!
				 (create-sound-spec ~sound-id ~(dissoc options-map :id))))
		(let [options-map (if (and (= 1 (count options)) (map? (first options)))
												(first options)
												(apply hash-map options))
					sound-id (or (:id options-map)
											 (name sound-name))]
			`(def ~sound-name
				 (register-sound!
					 (create-sound-spec ~sound-id ~(dissoc options-map :id)))))))