(ns cn.li.mcmod.client.platform-bridge
	"Platform-neutral client bridge injected by platform adapters."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private ^:dynamic *slot-key-down-fn* nil)
(defonce ^:private ^:dynamic *slot-key-tick-fn* nil)
(defonce ^:private ^:dynamic *slot-key-up-fn* nil)
(defonce ^:private ^:dynamic *movement-key-down-fn* nil)
(defonce ^:private ^:dynamic *movement-key-tick-fn* nil)
(defonce ^:private ^:dynamic *movement-key-up-fn* nil)
(defonce ^:private ^:dynamic *open-skill-tree-screen-fn* nil)
(defonce ^:private ^:dynamic *open-preset-editor-screen-fn* nil)
(defonce ^:private ^:dynamic *open-location-teleport-screen-fn* nil)
(defonce ^:private ^:dynamic *open-terminal-screen-fn* nil)
(defonce ^:private ^:dynamic *open-simple-gui-fn* nil)
(defonce ^:private ^:dynamic *play-intensify-local-effect-fn* nil)

(defn install-client-bridge!
	[{:keys [slot-key-down
					 slot-key-tick
					 slot-key-up
					 movement-key-down
					 movement-key-tick
					 movement-key-up
					 open-skill-tree-screen
					 open-preset-editor-screen
					 open-location-teleport-screen
					 open-terminal-screen
					 open-simple-gui
					 play-intensify-local-effect]}]
	(alter-var-root #'*slot-key-down-fn* (constantly slot-key-down))
	(alter-var-root #'*slot-key-tick-fn* (constantly slot-key-tick))
	(alter-var-root #'*slot-key-up-fn* (constantly slot-key-up))
	(alter-var-root #'*movement-key-down-fn* (constantly movement-key-down))
	(alter-var-root #'*movement-key-tick-fn* (constantly movement-key-tick))
	(alter-var-root #'*movement-key-up-fn* (constantly movement-key-up))
	(alter-var-root #'*open-skill-tree-screen-fn* (constantly open-skill-tree-screen))
	(alter-var-root #'*open-preset-editor-screen-fn* (constantly open-preset-editor-screen))
	(alter-var-root #'*open-location-teleport-screen-fn* (constantly open-location-teleport-screen))
	(alter-var-root #'*open-terminal-screen-fn* (constantly open-terminal-screen))
	(alter-var-root #'*open-simple-gui-fn* (constantly open-simple-gui))
	(alter-var-root #'*play-intensify-local-effect-fn* (constantly play-intensify-local-effect))
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

(defn play-intensify-local-effect!
	[]
	(if *play-intensify-local-effect-fn*
		(*play-intensify-local-effect-fn*)
		(log/debug "Client bridge play-intensify-local-effect not available")))