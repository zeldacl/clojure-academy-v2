(ns cn.li.ac.client.platform-bridge
	"Compatibility wrapper over mcmod platform-neutral client bridge."
	(:require [cn.li.mcmod.client.platform-bridge :as bridge]))

(defn install-client-bridge!
	[bridge-map]
	(bridge/install-client-bridge! bridge-map))

(defn on-slot-key-down!
	[player-uuid key-idx]
	(bridge/on-slot-key-down! player-uuid key-idx))

(defn on-slot-key-tick!
	[player-uuid key-idx]
	(bridge/on-slot-key-tick! player-uuid key-idx))

(defn on-slot-key-up!
	[player-uuid key-idx]
	(bridge/on-slot-key-up! player-uuid key-idx))

(defn open-skill-tree-screen!
	([player-uuid]
	 (open-skill-tree-screen! player-uuid nil))
	([player-uuid learn-context]
	 (bridge/open-skill-tree-screen! player-uuid learn-context)))

(defn open-preset-editor-screen!
	[player-uuid]
	(bridge/open-preset-editor-screen! player-uuid))

(defn open-location-teleport-screen!
	([player-uuid]
	 (open-location-teleport-screen! player-uuid nil))
	([player-uuid payload]
	 (bridge/open-location-teleport-screen! player-uuid payload)))

(defn open-terminal-screen!
	[player]
	(bridge/open-terminal-screen! player))

(defn open-simple-gui!
	[gui-widget title]
	(bridge/open-simple-gui! gui-widget title))