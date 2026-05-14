(ns cn.li.ac.ability.state-manager
	"Phase C state manager facade.

	Publicly names the consolidated player-state service layer without forcing
	callers to know the internal split across core/accessor/tick namespaces."
	(:require [cn.li.ac.ability.service.player-state :as player-state]))

(def player-states player-state/player-states)

(def get-player-state player-state/get-player-state)
(def get-or-create-player-state! player-state/get-or-create-player-state!)
(def set-player-state! player-state/set-player-state!)
(def update-player-state! player-state/update-player-state!)
(def remove-player-state! player-state/remove-player-state!)
(def list-player-uuids player-state/list-player-uuids)

(def mark-dirty! player-state/mark-dirty!)
(def mark-clean! player-state/mark-clean!)
(def dirty? player-state/dirty?)

(def get-ability-data player-state/get-ability-data)
(def get-resource-data player-state/get-resource-data)
(def get-cooldown-data player-state/get-cooldown-data)
(def get-preset-data player-state/get-preset-data)
(def get-develop-data player-state/get-develop-data)

(def update-ability-data! player-state/update-ability-data!)
(def update-resource-data! player-state/update-resource-data!)
(def update-cooldown-data! player-state/update-cooldown-data!)
(def update-preset-data! player-state/update-preset-data!)
(def update-develop-data! player-state/update-develop-data!)

(def server-tick-player! player-state/server-tick-player!)