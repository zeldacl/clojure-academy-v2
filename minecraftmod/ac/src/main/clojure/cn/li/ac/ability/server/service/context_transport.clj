(ns cn.li.ac.ability.server.service.context-transport
	"Transport port for ability context server/client messaging.

	Owns send-function registration and provides transport operations used by
	lifecycle/runtime services.")

(defonce ^:private send-to-client-fn (atom nil))
(defonce ^:private send-to-server-fn (atom nil))

(defn register-send-fns!
	[{:keys [to-client to-server]}]
	(reset! send-to-client-fn to-client)
	(reset! send-to-server-fn to-server))

(defn send-to-client!
	[player-uuid msg-id payload]
	(when-let [f @send-to-client-fn]
		(f player-uuid msg-id payload)))

(defn send-to-server!
	[msg-id payload]
	(when-let [f @send-to-server-fn]
		(f msg-id payload)))