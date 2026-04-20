(ns cn.li.mcmod.sound.metadata
	"Metadata queries for sound event registration."
	(:require [cn.li.mcmod.sound.dsl :as sdsl]))

(defn get-all-sound-ids
	"Returns all registered sound IDs from the sound DSL."
	[]
	(sdsl/list-sounds))

(defn get-sound-spec
	"Returns the full sound spec for sound-id."
	[sound-id]
	(sdsl/get-sound sound-id))

(defn get-sound-registry-name
	"Returns the registry name for sound-id."
	[sound-id]
	(or (:registry-name (get-sound-spec sound-id))
			sound-id))