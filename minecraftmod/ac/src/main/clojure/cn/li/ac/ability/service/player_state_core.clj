(ns cn.li.ac.ability.service.player-state-core
	"Core player-state storage and lifecycle operations."
	(:require [cn.li.ac.ability.model.ability :as ad]
						[cn.li.ac.ability.model.resource :as rd]
						[cn.li.ac.ability.model.cooldown :as cd]
						[cn.li.ac.ability.model.preset :as pd]
						[cn.li.ac.ability.model.develop :as dev]))

(defonce player-states
	(atom {}))

(defn get-player-state [uuid-str]
	(get @player-states uuid-str))

(defn set-player-state! [uuid-str state]
	(swap! player-states assoc uuid-str state))

(defn update-player-state! [uuid-str f & args]
	(apply swap! player-states update uuid-str f args))

(defn fresh-state []
	{:ability-data  (ad/new-ability-data)
	 :resource-data (rd/new-resource-data)
	 :cooldown-data (cd/new-cooldown-data)
	 :preset-data   (pd/new-preset-data)
	 :develop-data  (dev/new-develop-data)
	 :terminal-data {:terminal-installed? false
									 :installed-apps #{}}
	 :dirty? false})

(defn get-or-create-player-state! [uuid-str]
	(or (get-player-state uuid-str)
			(let [s (fresh-state)]
				(set-player-state! uuid-str s)
				s)))

(defn remove-player-state! [uuid-str]
	(swap! player-states dissoc uuid-str))

(defn list-player-uuids []
	(keys @player-states))