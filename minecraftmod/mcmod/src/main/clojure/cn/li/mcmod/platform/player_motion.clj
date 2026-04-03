(ns cn.li.mcmod.platform.player-motion
  "Protocol for manipulating player motion and physics.

  No Minecraft imports.")

(defprotocol IPlayerMotion
  "Protocol for manipulating player motion and physics."

  (set-velocity! [this player-id x y z]
    "Set player velocity vector.
    Returns true if successful.")

  (add-velocity! [this player-id x y z]
    "Add to player velocity vector.
    Returns true if successful.")

  (get-velocity [this player-id]
    "Get player velocity as {:x :y :z}.
    Returns nil if player not found.")

  (set-on-ground! [this player-id on-ground?]
    "Set player on-ground state.
    Returns true if successful.")

  (is-on-ground? [this player-id]
    "Check if player is on ground.
    Returns boolean.")

  (dismount-riding! [this player-id]
    "Dismount player from riding entity.
    Returns true if successful."))

(def ^:dynamic *player-motion*
  "Dynamic var bound to IPlayerMotion implementation by platform layer."
  nil)
