(ns cn.li.ac.ability.service.player-state-dirty
	"Dirty-flag tracking for player-state synchronization."
	(:require [cn.li.ac.ability.service.player-state-core :as core]))

(defn mark-dirty! [uuid-str]
	(core/update-player-state! uuid-str assoc :dirty? true))

(defn mark-clean! [uuid-str]
	(core/update-player-state! uuid-str assoc :dirty? false))

(defn dirty? [uuid-str]
	(boolean (:dirty? (core/get-player-state uuid-str))))