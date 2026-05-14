(ns cn.li.ac.ability.server.handlers.context-handler
	"Context lifecycle request network handlers."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.service.dispatcher :as ctx]))

(defn handle-begin-link-context
	[{:keys [ctx-id skill-id]} player]
	(ctx-mgr/establish-context! (uuid/player-uuid-str player) ctx-id skill-id))

(defn handle-keepalive-context
	[{:keys [ctx-id]} _player]
	(ctx/update-keepalive! ctx-id))

(defn handle-terminate-context
	[{:keys [ctx-id]} _player]
	(ctx/terminate-context! ctx-id ctx-mgr/send-terminated-context!))

(defn handle-channel-context
	[{:keys [ctx-id channel payload]} _player]
	(ctx/ctx-send-to-local! ctx-id channel payload))