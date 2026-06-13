(ns cn.li.ac.tutorial.auto-give
  "Tutorial item auto-give support.

  Original AcademyCraft TutorialData had a 10-tick server-side scheduler that
  auto-spawned the tutorial item on login (config flag `giveCloudTerminal`).

  Current implementation: the tutorial item's :on-right-click handler checks
  `tutorial-acquired?` and marks it on first use.  A full periodic auto-give
  on login requires a server-tick hook with Player reference, which needs
  platform lifecycle integration — deferred to a future enhancement.

  Provides the state-check function shared between right-click handler
  and (future) login hook."
  (:require [cn.li.ac.tutorial.player :as tut-player]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.util.log :as log]))

(def tutorial-item-id "my_mod:tutorial")

(defn ensure-acquired!
  "Ensure the player has been marked as tutorial-acquired.  Called from the
  tutorial item's on-right-click handler (server side) when the player uses
  the tutorial item for the first time.

  Also gives a spare tutorial item so the player keeps one after use
  (the item is not consumed by right-click, matching original AC behavior)."
  [session-id uuid-str player]
  (when-not (tut-player/tutorial-acquired? session-id uuid-str)
    (try
      (when-let [stack (pitem/create-item-stack-by-id tutorial-item-id 1)]
        (entity/player-give-item-stack! player stack))
      (catch Exception e
        (log/warn "Failed to give spare tutorial item:" (ex-message e))))
    (tut-player/mark-tutorial-acquired! session-id uuid-str)
    (log/info "Tutorial item acquired for player" {:uuid uuid-str})))
