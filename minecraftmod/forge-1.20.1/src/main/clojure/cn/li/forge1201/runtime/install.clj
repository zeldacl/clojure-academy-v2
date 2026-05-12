(ns cn.li.forge1201.runtime.install
	"Forge runtime adapter installer.

	Centralizes runtime adapter installation so entry/lifecycle namespaces stay focused
	on event wiring only."
	(:require [cn.li.forge1201.runtime.entity-damage :as entity-damage]
					[cn.li.forge1201.runtime.raycast :as raycast]
					[cn.li.forge1201.runtime.interop :as interop]
					[cn.li.forge1201.runtime.world-effects :as world-effects]
					[cn.li.forge1201.runtime.potion-effects :as potion-effects]
					[cn.li.forge1201.runtime.teleportation :as teleportation]
					[cn.li.forge1201.runtime.saved-locations :as saved-locations]
					[cn.li.forge1201.runtime.player-motion :as player-motion]
					[cn.li.forge1201.runtime.entity-motion :as entity-motion]
					[cn.li.forge1201.runtime.entity-query :as entity-query]
					[cn.li.forge1201.runtime.block-manipulation :as block-manipulation]
					[cn.li.forge1201.runtime.damage-interception :as damage-interception]))

(defn install-runtime-adapters!
	[]
	(entity-damage/install-entity-damage!)
	(raycast/install-raycast!)
	(interop/install-runtime-interop!)
	(world-effects/install-world-effects!)
	(potion-effects/install-potion-effects!)
	(teleportation/install-teleportation!)
	(saved-locations/install-saved-locations!)
	(player-motion/install-player-motion!)
	(entity-motion/install-entity-motion!)
	(entity-query/install-entity-query!)
	(block-manipulation/install-block-manipulation!)
	(damage-interception/install-damage-interception!)
	nil)