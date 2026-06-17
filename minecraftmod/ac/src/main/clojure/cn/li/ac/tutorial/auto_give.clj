(ns cn.li.ac.tutorial.auto-give
  "Tutorial item auto-give on first player login.

  Original AcademyCraft TutorialData had a 10-tick server-side scheduler that
  auto-spawned the tutorial item on first login (config flag `giveCloudTerminal`,
  default true).  This function is called from the Forge lifecycle layer after
  player state is loaded, matching the original AC behavior.

  The tutorial item is NOT consumed on use (cn.li.ac.tutorial.item sets {:consume? false}),
  so the auto-give only needs to run once per player lifetime."
  (:require [cn.li.ac.tutorial.config :as tut-config]
            [cn.li.ac.tutorial.player :as tut-player]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.util.log :as log]))

(def tutorial-item-id "my_mod:tutorial")

(defn auto-give-on-login!
  "Check and perform tutorial item auto-give on player login.

  Respects the `give-cloud-terminal` config flag (default true).
  Idempotent: only grants the item once per player, tracked by the
  tutorial-acquired? flag in the player's tutorial state.

  Args:
    session-id  - server session identifier ([:server identity-hash])
    uuid-str    - player UUID string
    player      - platform player entity (implements IEntityOps)

  Returns truthy if the item was granted, nil otherwise."
  [session-id uuid-str player]
  (when (tut-config/give-cloud-terminal-enabled?)
    (when-not (tut-player/tutorial-acquired? session-id uuid-str)
      (try
        (when-let [stack (pitem/create-item-stack-by-id tutorial-item-id 1)]
          (entity/player-give-item-stack! player stack)
          (tut-player/mark-tutorial-acquired! session-id uuid-str)
          (log/info "Tutorial item auto-given to player" {:uuid uuid-str}))
        (catch Exception e
          (log/warn "Failed to auto-give tutorial item:" (ex-message e)))))))
