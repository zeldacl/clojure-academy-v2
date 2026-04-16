(ns cn.li.mcmod.platform.entity-motion
	"Protocol for manipulating generic entity motion (not only players).

	Platform layer implements this protocol and binds to *entity-motion*.
	Game logic (ac) uses it to apply knockback/impulse without Minecraft imports.")

(defprotocol IEntityMotion
	"Generic entity motion control."

	(set-velocity! [this world-id entity-uuid x y z]
		"Set absolute velocity of target entity.
		Returns true when successful.")

	(add-velocity! [this world-id entity-uuid x y z]
		"Add velocity delta to target entity.
		Returns true when successful.")

	(discard-entity! [this world-id entity-uuid]
		"Discard/remove target entity from world.
		Returns true when successful.")

	(get-velocity [this world-id entity-uuid]
		"Get target entity velocity map {:x :y :z}.
		Returns nil when entity is not found."))

(def ^:dynamic *entity-motion*
	"Bound by platform layer to IEntityMotion implementation.
	nil until platform init runs."
	nil)