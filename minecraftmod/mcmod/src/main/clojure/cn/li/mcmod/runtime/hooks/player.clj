(ns cn.li.mcmod.runtime.hooks.player
  "Player lifecycle/state hook surface (delegates to hooks-core during migration)."
  (:require [cn.li.mcmod.runtime.hooks-core :as hooks-core]))

(def register-power-runtime-hooks! hooks-core/register-power-runtime-hooks!)
(def on-player-login! hooks-core/on-player-login!)
(def on-player-logout! hooks-core/on-player-logout!)
(def on-player-clone! hooks-core/on-player-clone!)
(def on-player-death! hooks-core/on-player-death!)
(def on-player-tick! hooks-core/on-player-tick!)
(def list-player-uuids hooks-core/list-player-uuids)
(def get-player-state hooks-core/get-player-state)
(def set-player-state! hooks-core/set-player-state!)
(def get-or-create-player-state! hooks-core/get-or-create-player-state!)
(def fresh-player-state hooks-core/fresh-player-state)
(def build-sync-payload hooks-core/build-sync-payload)
(def mark-player-clean! hooks-core/mark-player-clean!)
(def get-max-saved-locations hooks-core/get-max-saved-locations)
