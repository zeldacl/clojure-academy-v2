(ns cn.li.mc1201.client.overlay.state
	(:require [cn.li.mc1201.client.session :as client-session]))

(defonce ^:private client-activated-overlay (atom {}))

(defn get-client-activated
	[owner]
	(get @client-activated-overlay (client-session/owner-key owner)))

(defn set-client-activated!
	[owner v]
	(swap! client-activated-overlay assoc (client-session/owner-key owner) (boolean v))
	nil)

(defn clear-client-activated!
	[owner]
	(swap! client-activated-overlay dissoc (client-session/owner-key owner))
	nil)

(defn clear-client-overlay-session!
	[client-session-id]
	(swap! client-activated-overlay
				 (fn [states]
					 (into {}
								 (remove (fn [[[entry-session-id _player-uuid] _value]]
													 (= client-session-id entry-session-id)))
								 states)))
	nil)

(defn overlay-state-snapshot
	[]
	@client-activated-overlay)

(defn reset-client-activated-for-test!
	([]
	 (reset-client-activated-for-test! {}))
	([snapshot]
	 (reset! client-activated-overlay (or snapshot {}))
	 nil))
