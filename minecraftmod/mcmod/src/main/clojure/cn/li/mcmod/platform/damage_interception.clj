(ns cn.li.mcmod.platform.damage-interception
  "Protocol for intercepting and modifying damage events.

  This allows skills to react to incoming damage and modify it.
  Platform (forge) implements this protocol and registers event handlers.

  No Minecraft imports.")

(defprotocol IDamageInterception
  "Damage event interception for skill reactions."

  (register-damage-handler! [this handler-id handler-fn priority]
    "Register a damage handler function.
    - handler-id: keyword identifier for this handler
    - handler-fn: (fn [player-id attacker-id damage damage-source] -> [modified-damage metadata])
    - priority: int (lower = earlier, default 100)
    Returns: true if registered successfully")

  (unregister-damage-handler! [this handler-id]
    "Unregister a damage handler.
    Returns: true if unregistered successfully")

  (get-active-handlers [this]
    "Get list of active handler IDs.
    Returns: seq of handler-id keywords"))

(def ^:dynamic *damage-interception*
  "Bound by platform (forge) to a reified IDamageInterception implementation.
  nil until platform init runs."
  nil)
