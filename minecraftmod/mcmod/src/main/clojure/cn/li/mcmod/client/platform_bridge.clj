(ns cn.li.mcmod.client.platform-bridge
	"Platform-neutral client bridge injected by platform adapters."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private ^:dynamic *slot-key-down-fn* nil)
(defonce ^:private ^:dynamic *slot-key-tick-fn* nil)
(defonce ^:private ^:dynamic *slot-key-up-fn* nil)
(defonce ^:private ^:dynamic *open-skill-tree-screen-fn* nil)
(defonce ^:private ^:dynamic *open-preset-editor-screen-fn* nil)
(defonce ^:private ^:dynamic *open-location-teleport-screen-fn* nil)
(defonce ^:private ^:dynamic *open-terminal-screen-fn* nil)
(defonce ^:private ^:dynamic *open-simple-gui-fn* nil)

(defn install-client-bridge!
	[{:keys [slot-key-down
					 slot-key-tick
					 slot-key-up
					 open-skill-tree-screen
					 open-preset-editor-screen
					 open-location-teleport-screen
					 open-terminal-screen
					 open-simple-gui]}]
	(alter-var-root #'*slot-key-down-fn* (constantly slot-key-down))
	(alter-var-root #'*slot-key-tick-fn* (constantly slot-key-tick))
	(alter-var-root #'*slot-key-up-fn* (constantly slot-key-up))
	(alter-var-root #'*open-skill-tree-screen-fn* (constantly open-skill-tree-screen))
	(alter-var-root #'*open-preset-editor-screen-fn* (constantly open-preset-editor-screen))
	(alter-var-root #'*open-location-teleport-screen-fn* (constantly open-location-teleport-screen))
	(alter-var-root #'*open-terminal-screen-fn* (constantly open-terminal-screen))
	(alter-var-root #'*open-simple-gui-fn* (constantly open-simple-gui))
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

(defn open-skill-tree-screen!
	([player-uuid]
	 (open-skill-tree-screen! player-uuid nil))
	([player-uuid learn-context]
	 (if *open-skill-tree-screen-fn*
		 (*open-skill-tree-screen-fn* player-uuid learn-context)
		 (log/debug "Client bridge node-tree screen not available"))))

(defn open-preset-editor-screen!
	[player-uuid]
	(if *open-preset-editor-screen-fn*
		(*open-preset-editor-screen-fn* player-uuid)
		(log/debug "Client bridge preset-editor screen not available")))

(defn open-location-teleport-screen!
	([player-uuid]
	 (open-location-teleport-screen! player-uuid nil))
	([player-uuid payload]
	 (if *open-location-teleport-screen-fn*
		 (*open-location-teleport-screen-fn* player-uuid payload)
		 (log/debug "Client bridge location-teleport screen not available"))))

(defn open-terminal-screen!
	[player]
	(if *open-terminal-screen-fn*
		(*open-terminal-screen-fn* player)
		(log/debug "Client bridge terminal screen not available")))

(defn open-simple-gui!
	[gui-widget title]
	(if *open-simple-gui-fn*
		(*open-simple-gui-fn* gui-widget title)
		(log/debug "Client bridge simple GUI not available")))