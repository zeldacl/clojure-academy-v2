(ns cn.li.mcmod.platform.potion-effects
  "Protocol for potion effect application.

  Platform (forge) implements this protocol and binds to *potion-effects*.
  Game logic (ac) calls protocol methods without importing Minecraft classes.")

(defprotocol IPotionEffects
  "Potion effect application and removal."

  (apply-potion-effect! [this player-uuid effect-type duration amplifier]
    "Apply a potion effect to a player.
    - player-uuid: string (player UUID)
    - effect-type: keyword (:speed :slowness :jump-boost :regeneration :strength :resistance :hunger :blindness)
    - duration: int (ticks, 20 ticks = 1 second)
    - amplifier: int (0 = level I, 1 = level II, etc.)
    Returns: true if applied successfully, false otherwise")

  (remove-potion-effect! [this player-uuid effect-type]
    "Remove a potion effect from a player.
    - player-uuid: string (player UUID)
    - effect-type: keyword (same as apply-potion-effect!)
    Returns: true if removed successfully, false otherwise")

  (has-potion-effect? [this player-uuid effect-type]
    "Check if player has a specific potion effect.
    - player-uuid: string (player UUID)
    - effect-type: keyword
    Returns: true if player has the effect, false otherwise")

  (clear-all-effects! [this player-uuid]
    "Remove all potion effects from a player.
    - player-uuid: string (player UUID)
    Returns: true if cleared successfully, false otherwise"))

(def ^:dynamic *potion-effects*
  "Bound by platform (forge) to a reified IPotionEffects implementation.
  nil until platform init runs."
  nil)
