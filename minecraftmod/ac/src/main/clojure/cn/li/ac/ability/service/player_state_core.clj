(ns cn.li.ac.ability.service.player-state-core
	"Core player-state storage and lifecycle operations."
	(:require [cn.li.ac.ability.model.ability :as ad]
						[cn.li.ac.ability.model.resource :as rd]
						[cn.li.ac.ability.model.cooldown :as cd]
						[cn.li.ac.ability.model.preset :as pd]
						[cn.li.ac.ability.model.develop :as dev]
						[cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn create-player-state-runtime
	[]
	{::runtime ::player-state-runtime
	 :player-states* (atom {})})

(def ^:dynamic *player-state-runtime* nil)

(defonce ^:private installed-player-state-runtime
	(create-player-state-runtime))

(defn- player-state-runtime?
	[runtime]
	(and (map? runtime)
			 (= ::player-state-runtime (::runtime runtime))
			 (some? (:player-states* runtime))))

(defn call-with-player-state-runtime
	[runtime f]
	(when-not (player-state-runtime? runtime)
		(throw (ex-info "Expected player state runtime"
							{:runtime runtime})))
	(binding [*player-state-runtime* runtime]
		(f)))

(defmacro with-player-state-runtime
	[runtime & body]
	`(call-with-player-state-runtime ~runtime (fn [] ~@body)))

(defn- current-player-state-runtime
	[]
	(or *player-state-runtime*
			installed-player-state-runtime))

(defn- player-states-atom
	[]
	(:player-states* (current-player-state-runtime)))

(defn- player-states-snapshot
	[]
	@(player-states-atom))

(defn- update-player-states!
	[f & args]
	(apply swap! (player-states-atom) f args))

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
	(get (player-states-snapshot) (player-state-key uuid-str)))

(defn set-player-state! [uuid-str state]
	(update-player-states! assoc (player-state-key uuid-str) state))

(defn update-player-state! [uuid-str f & args]
	(apply update-player-states! update (player-state-key uuid-str) f args))

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
	(update-player-states! dissoc (player-state-key uuid-str)))

(defn clear-session-player-states!
	"Remove all player states owned by one server/client session."
	[owner-or-session-id]
	(let [sid (if (map? owner-or-session-id)
					(owner-session-id owner-or-session-id)
					owner-or-session-id)]
		(update-player-states!
				 (fn [states]
					 (into {}
							 (remove (fn [[state-key _state]]
										 (= sid (first (normalize-state-key state-key)))))
							 states))))
	nil)

(defn list-player-uuids []
	(let [sid (session-id)]
		(->> (player-states-snapshot)
				 (keep (fn [[k _state]]
							 (let [[entry-session entry-uuid] (normalize-state-key k)]
							 (when (= sid entry-session)
									 entry-uuid))))
				 distinct)))

(defn snapshot-player-states []
	(player-states-snapshot))

(defn reset-player-states-for-test!
	([]
	 (reset-player-states-for-test! {}))
	([states]
	 (reset! (player-states-atom)
					 (into {}
							 (map (fn [[k state]] [(normalize-state-key k) state]))
							 states))))