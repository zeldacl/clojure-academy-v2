(ns cn.li.mcmod.platform.runtime-interop
	"Canonical runtime-side platform interop for world/player queries.")

(defprotocol IRuntimeInterop
	(get-player-view [this player-uuid]
		"Returns map with world and eye/look vectors.")
	(get-player-main-hand-item [this player-uuid]
		"Returns platform item stack object or nil.")
	(get-block-entity-at [this world-id x y z]
		"Returns platform block entity at coordinates or nil."))

(def ^:dynamic *runtime-interop*
	"Bound by platform (forge/fabric) to runtime interop implementation."
	nil)