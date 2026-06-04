(ns cn.li.mcmod.gui.sync-api
	"Shared GUI block-state sync dispatch contract.

	Server broadcast uses the same platform-version multimethod pattern as
	`mcmod.network.client/send-request`: each loader registers a `defmethod`;
	missing dispatch fails fast via `:default`."
	(:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]))

(def BLOCK-GUI-STATE-MSG-ID
	"ac/gui-block-state-sync")

(def ^:private err-type ::gui-broadcast-unavailable)

(defmulti broadcast-gui-state!*
	(fn [_world _pos _sync-data]
		(platform-dispatch/current-platform-version)))

(defmethod broadcast-gui-state!* :default
	[_world _pos _sync-data]
	(throw (ex-info "No GUI block-state broadcast for platform"
									{:type err-type
									 :platform (platform-dispatch/current-platform-version)
									 :registered (vec (keys (methods broadcast-gui-state!*)))})))

(defn assert-gui-broadcast-dispatch!
	"Fail fast when loader forgot to register `broadcast-gui-state!*` for this platform.
	Call from loader GUI network bootstrap after the defmethod namespace is loaded."
	[platform-key]
	(when-not (get (methods broadcast-gui-state!*) platform-key)
		(throw (ex-info "GUI block-state broadcast defmethod not registered"
										{:type err-type
										 :platform platform-key
										 :registered (vec (keys (methods broadcast-gui-state!*)))})))
	nil)