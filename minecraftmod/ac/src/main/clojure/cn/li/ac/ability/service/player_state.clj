(ns cn.li.ac.ability.service.player-state
	"Public aggregate API for player-state operations.

	Implementation is split into focused namespaces:
	- player-state-core
	- player-state-dirty
	- player-state-accessors
	- player-state-tick

	This namespace is intentionally retained as the stable service surface for
	callers that need the complete player-state toolkit."
	(:require [cn.li.ac.ability.service.player-state-core :as core]
					[cn.li.ac.ability.service.player-state-dirty :as dirty]
					[cn.li.ac.ability.service.player-state-accessors :as accessors]
					[cn.li.ac.ability.service.player-state-tick :as tick]))

(def get-player-state core/get-player-state)
(def set-player-state! core/set-player-state!)
(def update-player-state! core/update-player-state!)
(def fresh-state core/fresh-state)
(def get-or-create-player-state! core/get-or-create-player-state!)
(def remove-player-state! core/remove-player-state!)
(def list-player-uuids core/list-player-uuids)
(def snapshot-player-states core/snapshot-player-states)
(def reset-player-states-for-test! core/reset-player-states-for-test!)

(def mark-dirty! dirty/mark-dirty!)
(def mark-clean! dirty/mark-clean!)
(def dirty? dirty/dirty?)

(def get-ability-data accessors/get-ability-data)
(def get-resource-data accessors/get-resource-data)
(def get-cooldown-data accessors/get-cooldown-data)
(def get-preset-data accessors/get-preset-data)
(def get-develop-data accessors/get-develop-data)

(def update-ability-data! accessors/update-ability-data!)
(def update-resource-data! accessors/update-resource-data!)
(def update-cooldown-data! accessors/update-cooldown-data!)
(def update-preset-data! accessors/update-preset-data!)
(def update-develop-data! accessors/update-develop-data!)

(def server-tick-player! tick/server-tick-player!)