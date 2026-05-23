(ns cn.li.ac.ability.discovery
	"Discovery/registration layer for AC ability content.

	This namespace now builds provider lists from a classpath scanner,
	while preserving the existing provider registry API surface."
	(:require [cn.li.ac.discovery.registry :as discovery-registry]
					[cn.li.ac.discovery.scanner :as scanner]
					[cn.li.mcmod.util.log :as log]))

(defonce ^:private bootstrap-attempts* (atom 0))

(declare register-provider! registered-providers)

(def ^:private fallback-providers
	[{:id :generic
		:priority 10
		:skill-namespaces
		'[cn.li.ac.content.ability.generic.brain-course
			cn.li.ac.content.ability.generic.brain-course-advanced
			cn.li.ac.content.ability.generic.mind-course]}
	 {:id :electromaster
		:priority 20
		:skill-namespaces
		'[cn.li.ac.content.ability.electromaster.body-intensify
			cn.li.ac.content.ability.electromaster.current-charging
			cn.li.ac.content.ability.electromaster.mag-manip
			cn.li.ac.content.ability.electromaster.mag-movement
			cn.li.ac.content.ability.electromaster.railgun
			cn.li.ac.content.ability.electromaster.thunder-bolt
			cn.li.ac.content.ability.electromaster.thunder-clap
			cn.li.ac.content.ability.electromaster.mine-detect
			cn.li.ac.content.ability.electromaster.arc-gen]
		:fx-namespaces
		'[cn.li.ac.content.ability.electromaster.railgun-fx
			cn.li.ac.content.ability.electromaster.mag-movement-fx
			cn.li.ac.content.ability.electromaster.thunder-bolt-fx
			cn.li.ac.content.ability.electromaster.thunder-clap-fx
			cn.li.ac.content.ability.electromaster.mine-detect-fx]}
	 {:id :meltdowner
		:priority 30
		:skill-namespaces
		'[cn.li.ac.content.ability.meltdowner.meltdowner
			cn.li.ac.content.ability.meltdowner.electron-bomb
			cn.li.ac.content.ability.meltdowner.scatter-bomb
			cn.li.ac.content.ability.meltdowner.light-shield
			cn.li.ac.content.ability.meltdowner.mine-ray-basic
			cn.li.ac.content.ability.meltdowner.mine-ray-expert
			cn.li.ac.content.ability.meltdowner.mine-ray-luck
			cn.li.ac.content.ability.meltdowner.ray-barrage
			cn.li.ac.content.ability.meltdowner.jet-engine
			cn.li.ac.content.ability.meltdowner.electron-missile
			cn.li.ac.content.ability.meltdowner.rad-intensify]
		:fx-namespaces
		'[cn.li.ac.content.ability.meltdowner.meltdowner-fx
			cn.li.ac.content.ability.meltdowner.electron-bomb-fx
			cn.li.ac.content.ability.meltdowner.scatter-bomb-fx
			cn.li.ac.content.ability.meltdowner.light-shield-fx
			cn.li.ac.content.ability.meltdowner.mine-ray-fx
			cn.li.ac.content.ability.meltdowner.ray-barrage-fx
			cn.li.ac.content.ability.meltdowner.jet-engine-fx
			cn.li.ac.content.ability.meltdowner.electron-missile-fx]}
	 {:id :teleporter
		:priority 40
		:skill-namespaces
		'[cn.li.ac.content.ability.teleporter.location-teleport
			cn.li.ac.content.ability.teleporter.mark-teleport
			cn.li.ac.content.ability.teleporter.threatening-teleport
			cn.li.ac.content.ability.teleporter.penetrate-teleport
			cn.li.ac.content.ability.teleporter.flesh-ripping
			cn.li.ac.content.ability.teleporter.shift-teleport
			cn.li.ac.content.ability.teleporter.flashing
			cn.li.ac.content.ability.teleporter.dim-folding-theorem
			cn.li.ac.content.ability.teleporter.space-fluct]
		:fx-namespaces
		'[cn.li.ac.content.ability.teleporter.mark-teleport-fx
			cn.li.ac.content.ability.teleporter.threatening-teleport-fx
			cn.li.ac.content.ability.teleporter.teleporter-crit-fx
			cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
			cn.li.ac.content.ability.teleporter.flesh-ripping-fx
			cn.li.ac.content.ability.teleporter.shift-teleport-fx
			cn.li.ac.content.ability.teleporter.flashing-fx]}
	 {:id :vecmanip
		:priority 50
		:skill-namespaces
		'[cn.li.ac.content.ability.vecmanip.blood-retrograde
			cn.li.ac.content.ability.vecmanip.directed-blastwave
			cn.li.ac.content.ability.vecmanip.directed-shock
			cn.li.ac.content.ability.vecmanip.groundshock
			cn.li.ac.content.ability.vecmanip.plasma-cannon
			cn.li.ac.content.ability.vecmanip.storm-wing
			cn.li.ac.content.ability.vecmanip.vec-accel
			cn.li.ac.content.ability.vecmanip.vec-deviation
			cn.li.ac.content.ability.vecmanip.vec-reflection]
		:fx-namespaces
		'[cn.li.ac.content.ability.vecmanip.blood-retrograde-fx
			cn.li.ac.content.ability.vecmanip.directed-blastwave-fx
			cn.li.ac.content.ability.vecmanip.directed-shock-fx
			cn.li.ac.content.ability.vecmanip.groundshock-fx
			cn.li.ac.content.ability.vecmanip.plasma-cannon-fx
			cn.li.ac.content.ability.vecmanip.storm-wing-fx
			cn.li.ac.content.ability.vecmanip.vec-accel-fx
			cn.li.ac.content.ability.vecmanip.vec-deviation-fx
			cn.li.ac.content.ability.vecmanip.vec-reflection-fx]}])

(defn- provider-by-id
	[provider-id]
	(some #(when (= (:id %) provider-id) %) (registered-providers)))

(defn- merge-provider!
	[provider]
	(let [existing (provider-by-id (:id provider))
				merged (if existing
						 (-> existing
								 (assoc :priority (min (long (:priority existing)) (long (:priority provider))))
								 (update :skill-namespaces #(vec (distinct (concat % (:skill-namespaces provider)))))
								 (update :fx-namespaces #(vec (distinct (concat % (:fx-namespaces provider))))))
						 provider)]
		(register-provider! merged)))

(defn register-provider!
	"Register or replace an ability content provider."
	[provider]
	(discovery-registry/register-provider! provider))

(defn unregister-provider!
	[provider-id]
	(discovery-registry/unregister-provider! provider-id))

(defn registered-providers
	[]
	(discovery-registry/registered-providers))

(defn bootstrap-default-providers!
	"Discover and register built-in ability providers from classpath.

	Safe to call repeatedly."
	[]
	(when (or (zero? (count (registered-providers)))
					(< @bootstrap-attempts* 1))
		(swap! bootstrap-attempts* inc)
		(let [providers (scanner/discover-ability-providers)]
			(if (empty? providers)
				(log/warn "Ability discovery returned no providers; check classpath/resource layout")
				(doseq [provider providers]
					(register-provider! provider)))
			(doseq [provider fallback-providers]
				(merge-provider! provider))))
	(registered-providers))

(defn discovered-skill-namespaces
	"Return server-side skill namespaces in deterministic load order."
	[]
	(bootstrap-default-providers!)
	(->> (registered-providers)
			 (mapcat :skill-namespaces)
			 distinct
			 vec))

(defn discovered-fx-namespaces
	"Return client-side FX namespaces in deterministic load order."
	[]
	(bootstrap-default-providers!)
	(->> (registered-providers)
			 (mapcat :fx-namespaces)
			 distinct
			 vec))

(defn register-skill-namespace!
	"Convenience helper for external providers contributing one skill namespace."
	([provider-id ns-sym]
	 (register-skill-namespace! provider-id ns-sym 100))
	([provider-id ns-sym priority]
	 (let [existing (or (some #(when (= (:id %) provider-id) %) (registered-providers))
									 {:id provider-id :priority priority})]
		 (register-provider! (update existing :skill-namespaces (fnil conj []) ns-sym)))))

(defn register-fx-namespace!
	"Convenience helper for external providers contributing one FX namespace."
	([provider-id ns-sym]
	 (register-fx-namespace! provider-id ns-sym 100))
	([provider-id ns-sym priority]
	 (let [existing (or (some #(when (= (:id %) provider-id) %) (registered-providers))
									 {:id provider-id :priority priority})]
		 (register-provider! (update existing :fx-namespaces (fnil conj []) ns-sym)))))