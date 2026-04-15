(ns cn.li.mcmod.platform.ability-interop
	"Platform-neutral helpers for ability runtime interop.

	This bridge exposes a minimal set of server-side queries that ability logic
	needs for runtime interactions (player view, held item, block entity lookup)
	without importing Minecraft classes in AC layer.")

(defprotocol IAbilityInterop
	(get-player-view [this player-uuid]
		"Return player's current view data map, or nil when unavailable.

		Expected keys:
		- :world-id string
		- :x/:y/:z doubles (eye position)
		- :look-x/:look-y/:look-z doubles (normalized look direction)")

	(get-player-main-hand-item [this player-uuid]
		"Return player's main-hand ItemStack object, or nil when empty/unavailable.")

	(get-block-entity-at [this world-id x y z]
		"Return block entity object at world-id/x/y/z, or nil when unavailable."))

(def ^:dynamic *ability-interop*
	"Bound by platform (forge/fabric) to a reified IAbilityInterop implementation.
	nil until platform init runs."
	nil)