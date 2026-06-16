(ns cn.li.mcmod.gui.container-state
	"Platform-neutral container lifecycle state for GUI infrastructure.

	This namespace owns menu/container lookup state used by shared Minecraft GUI
	code. AC installs business callbacks separately; platform adapters should not
	round-trip this generic state through AC.

	Runtime state is owned by an explicit container-state runtime component. In
	production that component is installed at load time; tests may
	bind a fresh runtime explicitly via `call-with-container-state-runtime`."
	(:require [cn.li.mcmod.platform.entity :as entity]
						[cn.li.mcmod.util.log :as log]))

(defn initial-container-state
	[]
	{:active-containers {}
	 :player-containers {}
	 :menu-containers {}
	 :containers-by-id {}})

(defn create-container-state-runtime
	[]
	{::runtime ::container-state-runtime
	 :state* (atom (initial-container-state))})

(def ^:dynamic *container-state-runtime* nil)

(defn- container-state-runtime?
	[runtime]
	(and (map? runtime)
			 (= ::container-state-runtime (::runtime runtime))
			 (some? (:state* runtime))))

(defn call-with-container-state-runtime
	[runtime f]
	(let [runtime (if (container-state-runtime? runtime)
									runtime
									(throw (ex-info "Expected container-state runtime"
																{:runtime runtime})))]
		(binding [*container-state-runtime* runtime]
			(f))))

(defonce ^:private installed-container-state-runtime
	(create-container-state-runtime))

(defn installed-runtime
	"Return the production container-state runtime installed at load time."
	[]
	installed-container-state-runtime)

(defn- current-runtime
	[]
	(or *container-state-runtime*
			installed-container-state-runtime))

(defn- state-atom
	[]
	(:state* (current-runtime)))

(defn- snapshot
	[]
	@(state-atom))

(defn- update-state!
	[f & args]
	(apply swap! (state-atom) f args))

(defn- require-owner-value
	[owner label value]
	(if (some? value)
		value
		(throw (ex-info (format "GUI container owner requires %s" label)
										{:owner owner
										 :required label}))))

(defn- player-key
	[player]
	(when player
		(some-> (entity/player-get-uuid player) str)))

(defn- session-key
	[owner]
	(require-owner-value owner ":server-session-id or :client-session-id"
											 (or (:server-session-id owner)
													 (:client-session-id owner))))

(defn- owner-player-key
	[owner]
	(require-owner-value owner ":player-uuid"
											 (or (some-> (:player-uuid owner) str)
													 (some-> (:player owner) player-key))))

(defn- owner-base-key
	[owner]
	[(session-key owner) (owner-player-key owner)])

(defn- container-owner-key
	[owner container-id]
	[(session-key owner) (owner-player-key owner) (int container-id)])

(defn- owner-key-prefix?
	[base-key state-key]
	(= base-key (subvec (vec state-key) 0 2)))

(defn- session-key-prefix?
	[session-id state-key]
	(= session-id (first state-key)))

(defn- container-runtime-id
	[container]
	(or (:container-id container)
			(:window-id container)
			(:id container)
			(System/identityHashCode container)))

(defn- active-container-key
	[owner container]
	(conj (owner-base-key owner) (container-runtime-id container)))

(defn- container-owner
	[container]
	(or (:owner container)
			(select-keys container [:server-session-id :client-session-id :player-uuid :player :owner :logical-side])))

(defn owner-from-container
	"Resolve an explicit owner map from a Clojure container."
	[container]
	(let [owner (container-owner container)
				player (:player owner)
				player-id (or (:player-uuid owner)
											(:player-uuid container)
											(some-> player player-key))]
		(cond-> owner
			player (assoc :player player)
			player-id (assoc :player-uuid player-id))))

(defn- remove-map-entries
	[m pred]
	(into {}
				(remove (fn [[k v]] (pred k v)))
				m))

(defn- register-active-container-state
	[state owner container]
	(assoc-in state [:active-containers (active-container-key owner container)] container))

(defn- unregister-active-container-state
	[state owner container]
	(update state :active-containers dissoc (active-container-key owner container)))

(defn- register-player-container-state
	[state owner container]
	(update-in state [:player-containers (owner-base-key owner)] (fnil conj []) container))

(defn- unregister-player-containers-state
	[state owner]
	(update state :player-containers dissoc (owner-base-key owner)))

(defn- unregister-player-container-state
	[state owner container]
	(let [k (owner-base-key owner)]
		(update state :player-containers
						(fn [by-player]
							(let [remaining (vec (remove #(identical? container %) (get by-player k)))]
								(if (seq remaining)
									(assoc by-player k remaining)
									(dissoc by-player k)))))))

(defn- register-menu-container-state
	[state menu container]
	(assoc-in state [:menu-containers menu] container))

(defn- unregister-menu-container-state
	[state menu]
	(update state :menu-containers dissoc menu))

(defn- register-container-by-id-state
	[state owner container-id container]
	(assoc-in state [:containers-by-id (container-owner-key owner container-id)] container))

(defn- unregister-container-by-id-state
	[state owner container-id]
	(update state :containers-by-id dissoc (container-owner-key owner container-id)))

(defn- clear-owner-state
	[state owner]
	(let [base-key (owner-base-key owner)]
		(-> state
				(update :active-containers
								remove-map-entries
								(fn [state-key _container]
									(owner-key-prefix? base-key state-key)))
				(update :player-containers dissoc base-key)
				(update :containers-by-id
								remove-map-entries
								(fn [state-key _container]
									(owner-key-prefix? base-key state-key)))
				(update :menu-containers
								remove-map-entries
								(fn [_menu container]
									(try
										(= base-key (owner-base-key (container-owner container)))
										(catch Exception _
											false)))))))

(defn- clear-session-state
	[state session-id]
	(-> state
			(update :active-containers
							remove-map-entries
							(fn [state-key _container]
								(session-key-prefix? session-id state-key)))
			(update :player-containers
							remove-map-entries
							(fn [state-key _containers]
								(session-key-prefix? session-id state-key)))
			(update :containers-by-id
							remove-map-entries
							(fn [state-key _container]
								(session-key-prefix? session-id state-key)))
			(update :menu-containers
							remove-map-entries
							(fn [_menu container]
								(try
									(= session-id (session-key (container-owner container)))
									(catch Exception _
										false))))))

(defn register-active-container!
	"Register a Clojure container as active for an explicit owner."
	[owner container]
	(let [state (update-state! register-active-container-state owner container)]
		(log/debug "Registered active GUI container; total=" (count (:active-containers state)))
		nil))

(defn unregister-active-container!
	"Unregister a Clojure container when its Minecraft menu is closed."
	[owner container]
	(let [state (update-state! unregister-active-container-state owner container)]
		(log/debug "Unregistered active GUI container; remaining=" (count (:active-containers state)))
		nil))

(defn list-active-containers
	"Return active containers. Zero-arity returns a global diagnostic/tick snapshot;
	 owner arity filters to one owner."
	([]
	 (vals (:active-containers (snapshot))))
	([owner]
	 (let [base-key (owner-base-key owner)]
		 (->> (:active-containers (snapshot))
					(keep (fn [[state-key container]]
									(when (owner-key-prefix? base-key state-key)
										container)))))))

(defn register-player-container!
	"Register a container for an explicit owner."
	[owner container]
	(let [k (owner-base-key owner)]
		(update-state! register-player-container-state owner container)
		(log/debug "Registered GUI container for owner" k))
	nil)

(defn unregister-player-container!
	"Remove an owner's active GUI container mapping."
	([owner]
	 (let [k (owner-base-key owner)]
		 (update-state! unregister-player-containers-state owner)
		 (log/debug "Unregistered GUI containers for owner" k))
	 nil)
	([owner container]
	 (let [k (owner-base-key owner)]
		 (update-state! unregister-player-container-state owner container)
		 (log/debug "Unregistered GUI container for owner" k))
	 nil))

(defn get-player-container
	"Get the active GUI container for an explicit owner."
	[owner]
	(peek (get-in (snapshot) [:player-containers (owner-base-key owner)])))

(defn get-player-containers
	"Get all active GUI containers for an explicit owner."
	[owner]
	(get-in (snapshot) [:player-containers (owner-base-key owner)] []))

(defn get-player-container-from-active
	"Find an active tabbed container for an explicit owner by scanning active containers."
	[owner]
	(first
		(filter #(contains? % :tab-index)
						(list-active-containers owner))))

(defn register-menu-container!
	"Register the Clojure container backing a Minecraft menu instance."
	[menu container]
	(when menu
		(update-state! register-menu-container-state menu container)
		(log/debug "Registered GUI container for menu" (type menu)))
	nil)

(defn unregister-menu-container!
	"Remove menu -> Clojure container mapping."
	[menu]
	(when menu
		(update-state! unregister-menu-container-state menu)
		(log/debug "Unregistered GUI container for menu" (type menu)))
	nil)

(defn get-container-for-menu
	"Get the Clojure container backing a Minecraft menu instance."
	[menu]
	(get-in (snapshot) [:menu-containers menu]))

(defn register-container-by-id!
	"Register a Clojure container by Minecraft containerId/window id."
	[owner container-id container]
	(when (some? container-id)
		(update-state! register-container-by-id-state owner container-id container)
		(log/debug "Registered GUI container by owner/id" (container-owner-key owner container-id)))
	nil)

(defn unregister-container-by-id!
	"Remove containerId/window id mapping."
	[owner container-id]
	(when (some? container-id)
		(update-state! unregister-container-by-id-state owner container-id)
		(log/debug "Unregistered GUI container by owner/id" (container-owner-key owner container-id)))
	nil)

(defn get-container-by-id
	"Get a Clojure container by Minecraft containerId/window id."
	[owner container-id]
	(when (some? container-id)
		(get-in (snapshot) [:containers-by-id (container-owner-key owner container-id)])))

(defn get-menu-container-id
  "Get a Minecraft menu/container window id via platform protocol."
  [menu]
  (when menu
    (try
      (entity/menu-get-container-id menu)
      (catch Exception _ nil))))

(defn resolve-container-for-menu
	"Resolve a Clojure container for a menu, falling back to containerId lookup."
	[menu]
	(get-container-for-menu menu))

(defn clear-all!
	"Clear all GUI runtime state. Intended for tests/reloads."
	[]
	(reset! (state-atom) (initial-container-state))
	nil)

(defn clear-owner-containers!
	"Clear GUI runtime state for one owner."
	[owner]
	(update-state! clear-owner-state owner)
	nil)

(defn clear-session-containers!
	"Clear GUI runtime state for one client/server session id."
	[session-id]
	(update-state! clear-session-state session-id)
	nil)

(defn container-state-snapshot
	"Return raw GUI runtime state for tests/diagnostics."
	[]
	(let [{:keys [active-containers player-containers menu-containers containers-by-id]} (snapshot)]
		{:active-containers active-containers
		 :player-containers player-containers
		 :menu-containers menu-containers
		 :containers-by-id containers-by-id}))