(ns cn.li.forge1201.runtime.install
	"Forge runtime adapter installer.

	Centralizes runtime adapter installation so entry/lifecycle namespaces stay focused
	on event wiring only."
	(:require [cn.li.mcmod.util.log :as log]))

(defn- try-install!
	[ns-sym fn-sym label]
	(try
		(require ns-sym)
		(if-let [f (resolve fn-sym)]
			(f)
			(log/warn "Install function not found for" label "(" fn-sym ")"))
		(catch Exception e
			(log/warn "Failed to install" label ":" (ex-message e)))))

(def ^:private runtime-install-plan
	[['cn.li.forge1201.runtime.entity-damage
		'cn.li.forge1201.runtime.entity-damage/install-entity-damage!
		"entity-damage"]
	 ['cn.li.forge1201.runtime.raycast
		'cn.li.forge1201.runtime.raycast/install-raycast!
		"raycast"]
	 ['cn.li.forge1201.runtime.interop
		'cn.li.forge1201.runtime.interop/install-runtime-interop!
		"runtime-interop"]
	 ['cn.li.forge1201.runtime.world-effects
		'cn.li.forge1201.runtime.world-effects/install-world-effects!
		"world-effects"]
	 ['cn.li.forge1201.runtime.potion-effects
		'cn.li.forge1201.runtime.potion-effects/install-potion-effects!
		"potion-effects"]
	 ['cn.li.forge1201.runtime.teleportation
		'cn.li.forge1201.runtime.teleportation/install-teleportation!
		"teleportation"]
	 ['cn.li.forge1201.runtime.saved-locations
		'cn.li.forge1201.runtime.saved-locations/install-saved-locations!
		"saved-locations"]
	 ['cn.li.forge1201.runtime.player-motion
		'cn.li.forge1201.runtime.player-motion/install-player-motion!
		"player-motion"]
	 ['cn.li.forge1201.runtime.entity-motion
		'cn.li.forge1201.runtime.entity-motion/install-entity-motion!
		"entity-motion"]
	 ['cn.li.forge1201.runtime.entity-query
		'cn.li.forge1201.runtime.entity-query/install-entity-query!
		"entity-query"]
	 ['cn.li.forge1201.runtime.block-manipulation
		'cn.li.forge1201.runtime.block-manipulation/install-block-manipulation!
		"block-manipulation"]
	 ['cn.li.forge1201.runtime.damage-interception
		'cn.li.forge1201.runtime.damage-interception/install-damage-interception!
		"damage-interception"]])

(defn install-runtime-adapters!
	[]
	(doseq [[ns-sym fn-sym label] runtime-install-plan]
		(try-install! ns-sym fn-sym label))
	nil)