(ns cn.li.mcmod.client.platform-bridge
	"Platform-neutral client bridge injected by platform adapters.

	The bridge is intentionally content-agnostic: content modules choose screen
	and effect keys, while platform adapters provide generic host functions."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private ^:dynamic *slot-key-down-fn* nil)
(defonce ^:private ^:dynamic *slot-key-tick-fn* nil)
(defonce ^:private ^:dynamic *slot-key-up-fn* nil)
(defonce ^:private ^:dynamic *movement-key-down-fn* nil)
(defonce ^:private ^:dynamic *movement-key-tick-fn* nil)
(defonce ^:private ^:dynamic *movement-key-up-fn* nil)
(defonce ^:private ^:dynamic *open-screen-fn* nil)
(defonce ^:private ^:dynamic *open-simple-gui-fn* nil)
(defonce ^:private ^:dynamic *run-client-effect-fn* nil)

(defn install-client-bridge!
	[{:keys [slot-key-down
					 slot-key-tick
					 slot-key-up
					 movement-key-down
					 movement-key-tick
					 movement-key-up
					 open-screen
					 open-simple-gui
					 run-client-effect]}]
	(alter-var-root #'*slot-key-down-fn* (constantly slot-key-down))
	(alter-var-root #'*slot-key-tick-fn* (constantly slot-key-tick))
	(alter-var-root #'*slot-key-up-fn* (constantly slot-key-up))
	(alter-var-root #'*movement-key-down-fn* (constantly movement-key-down))
	(alter-var-root #'*movement-key-tick-fn* (constantly movement-key-tick))
	(alter-var-root #'*movement-key-up-fn* (constantly movement-key-up))
	(alter-var-root #'*open-screen-fn* (constantly open-screen))
	(alter-var-root #'*open-simple-gui-fn* (constantly open-simple-gui))
	(alter-var-root #'*run-client-effect-fn* (constantly run-client-effect))
	nil)

(defn on-slot-key-down!
	[player-uuid key-idx]
	(if *slot-key-down-fn*
		(*slot-key-down-fn* player-uuid key-idx)
		(log/debug "Client bridge slot-key-down not available")))

(defn on-slot-key-tick!
	[player-uuid key-idx]
	(if *slot-key-tick-fn*
		(*slot-key-tick-fn* player-uuid key-idx)
		(log/debug "Client bridge slot-key-tick not available")))

(defn on-slot-key-up!
	[player-uuid key-idx]
	(if *slot-key-up-fn*
		(*slot-key-up-fn* player-uuid key-idx)
		(log/debug "Client bridge slot-key-up not available")))

(defn on-movement-key-down!
	[player-uuid movement-key]
	(if *movement-key-down-fn*
		(*movement-key-down-fn* player-uuid movement-key)
		(log/debug "Client bridge movement-key-down not available")))

(defn on-movement-key-tick!
	[player-uuid movement-key]
	(if *movement-key-tick-fn*
		(*movement-key-tick-fn* player-uuid movement-key)
		(log/debug "Client bridge movement-key-tick not available")))

(defn on-movement-key-up!
	[player-uuid movement-key]
	(if *movement-key-up-fn*
		(*movement-key-up-fn* player-uuid movement-key)
		(log/debug "Client bridge movement-key-up not available")))

(defn open-screen!
	"Open a content-owned screen through the installed platform host.

	`screen-key` is intentionally opaque to mcmod. `payload` is a content-owned
	data map interpreted by the installed host/content runtime."
	([screen-key]
	 (open-screen! screen-key nil))
	([screen-key payload]
	 (if *open-screen-fn*
		 (*open-screen-fn* screen-key payload)
		 (log/debug "Client bridge screen host not available" {:screen-key screen-key}))))

(defn open-simple-gui!
	[gui-widget title]
	(if *open-simple-gui-fn*
		(*open-simple-gui-fn* gui-widget title)
		(log/debug "Client bridge simple GUI not available")))

(defn run-client-effect!
	"Run a content-owned local client effect through the installed platform host."
	([effect-key]
	 (run-client-effect! effect-key nil))
	([effect-key payload]
	 (if *run-client-effect-fn*
		 (*run-client-effect-fn* effect-key payload)
		 (log/debug "Client bridge effect host not available" {:effect-key effect-key}))))