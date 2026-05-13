(ns cn.li.mcmod.runtime.lifecycle-hooks
  "Lifecycle/player runtime hook surface (compat wrapper)."
  (:require [cn.li.mcmod.runtime.hooks.player :as player-hooks]))

(def register-power-runtime-hooks! player-hooks/register-power-runtime-hooks!)
(def on-player-login! player-hooks/on-player-login!)
(def on-player-logout! player-hooks/on-player-logout!)
(def on-player-clone! player-hooks/on-player-clone!)
(def on-player-death! player-hooks/on-player-death!)
(def on-player-tick! player-hooks/on-player-tick!)
(def list-player-uuids player-hooks/list-player-uuids)
(def get-player-state player-hooks/get-player-state)
(def set-player-state! player-hooks/set-player-state!)
(def get-or-create-player-state! player-hooks/get-or-create-player-state!)
(def fresh-player-state player-hooks/fresh-player-state)
(def build-sync-payload player-hooks/build-sync-payload)
(def mark-player-clean! player-hooks/mark-player-clean!)
(def get-max-saved-locations player-hooks/get-max-saved-locations)
