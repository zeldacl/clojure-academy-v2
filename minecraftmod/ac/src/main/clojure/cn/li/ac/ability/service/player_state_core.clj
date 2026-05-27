(ns cn.li.ac.ability.service.player-state-core
	"Core player-state storage and lifecycle operations."
	(:require [cn.li.ac.ability.model.ability :as ad]
						[cn.li.ac.ability.model.resource :as rd]
						[cn.li.ac.ability.model.cooldown :as cd]
						[cn.li.ac.ability.model.preset :as pd]
						[cn.li.ac.ability.model.develop :as dev]
						[cn.li.mcmod.hooks.core :as runtime-hooks]))

(defonce ^:private player-states
	(atom {}))

(defn- require-session-id
	[owner session-id]
	(if (some? session-id)
		session-id
		(throw (ex-info "Player state owner requires :server-session-id/:client-session-id/:session-id"
								{:owner owner}))))

(defn- session-id
	[]
	(let [owner runtime-hooks/*player-state-owner*]
		(require-session-id owner
										(or (:server-session-id owner)
												(:client-session-id owner)
												(:session-id owner)))))

(defn player-state-key
	"Return the internal owner key for a player UUID in the current dynamic owner scope."
	[uuid-str]
	[(session-id) (str uuid-str)])

(defn- owner-session-id
	[owner]
	(require-session-id owner
						(or (:server-session-id owner)
								(:client-session-id owner)
								(:session-id owner))))

(defn- normalize-state-key
	[k]
	(if (vector? k)
		k
		[(session-id) (str k)]))

(defn get-player-state [uuid-str]
	(get @player-states (player-state-key uuid-str)))

(defn set-player-state! [uuid-str state]
	(swap! player-states assoc (player-state-key uuid-str) state))

(defn update-player-state! [uuid-str f & args]
	(apply swap! player-states update (player-state-key uuid-str) f args))

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
	(swap! player-states dissoc (player-state-key uuid-str)))

(defn clear-session-player-states!
	"Remove all player states owned by one server/client session."
	[owner-or-session-id]
	(let [sid (if (map? owner-or-session-id)
					(owner-session-id owner-or-session-id)
					owner-or-session-id)]
		(swap! player-states
				 (fn [states]
					 (into {}
							 (remove (fn [[state-key _state]]
										 (= sid (first (normalize-state-key state-key)))))
							 states))))
	nil)

(defn list-player-uuids []
	(let [sid (session-id)]
		(->> @player-states
				 (keep (fn [[k _state]]
							 (let [[entry-session entry-uuid] (normalize-state-key k)]
							 (when (= sid entry-session)
									 entry-uuid))))
				 distinct)))

(defn snapshot-player-states []
	@player-states)

(defn reset-player-states-for-test!
	([]
	 (reset-player-states-for-test! {}))
	([states]
	 (reset! player-states
					 (into {}
							 (map (fn [[k state]] [(normalize-state-key k) state]))
							 states))))