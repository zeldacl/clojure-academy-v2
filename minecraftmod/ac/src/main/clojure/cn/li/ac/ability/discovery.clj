(ns cn.li.ac.ability.discovery
	"Discovery/registration layer for AC ability content.

	This replaces giant hard-coded require lists in bootstrap namespaces with a
	deterministic provider registry. Providers contribute server-side skill
	namespaces and/or client-side FX namespaces, sorted by `:priority` then `:id`."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private providers* (atom {}))

(def ^:private default-providers
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

(defn- normalize-provider
	[{:keys [id priority skill-namespaces fx-namespaces]
		:or {priority 100}}]
	{:pre [(keyword? id)]}
	{:id id
	 :priority (long priority)
	 :skill-namespaces (vec (distinct (or skill-namespaces [])))
	 :fx-namespaces (vec (distinct (or fx-namespaces [])))})

(defn register-provider!
	"Register or replace an ability content provider."
	[provider]
	(let [provider* (normalize-provider provider)]
		(swap! providers* assoc (:id provider*) provider*)
		(log/debug "Registered ability content provider" (:id provider*))
		provider*))

(defn unregister-provider!
	[provider-id]
	(swap! providers* dissoc provider-id)
	nil)

(defn registered-providers
	[]
	(->> @providers*
			 vals
			 (sort-by (juxt :priority :id))
			 vec))

(defn bootstrap-default-providers!
	"Ensure built-in providers exist. Safe to call repeatedly."
	[]
	(doseq [provider default-providers]
		(register-provider! provider))
	(registered-providers))

(defn discovered-skill-namespaces
	"Return server-side skill namespaces in deterministic load order."
	[]
	(->> (registered-providers)
			 (mapcat :skill-namespaces)
			 distinct
			 vec))

(defn discovered-fx-namespaces
	"Return client-side FX namespaces in deterministic load order."
	[]
	(->> (registered-providers)
			 (mapcat :fx-namespaces)
			 distinct
			 vec))

(defn register-skill-namespace!
	"Convenience helper for external providers contributing one skill namespace."
	([provider-id ns-sym]
	 (register-skill-namespace! provider-id ns-sym 100))
	([provider-id ns-sym priority]
	 (let [existing (get @providers* provider-id {:id provider-id :priority priority})]
		 (register-provider! (update existing :skill-namespaces (fnil conj []) ns-sym)))))

(defn register-fx-namespace!
	"Convenience helper for external providers contributing one FX namespace."
	([provider-id ns-sym]
	 (register-fx-namespace! provider-id ns-sym 100))
	([provider-id ns-sym priority]
	 (let [existing (get @providers* provider-id {:id provider-id :priority priority})]
		 (register-provider! (update existing :fx-namespaces (fnil conj []) ns-sym)))))