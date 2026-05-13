(ns cn.li.mcmod.registry.core
	"Shared registry abstraction for atom-backed metadata registries.")

(defprotocol IRegistry
	(snapshot [this])
	(swap-state! [this f])
	(reset-state! [this new-state]))

(deftype AtomRegistry [state-ref]
	IRegistry
	(snapshot [_]
		@state-ref)
	(swap-state! [_ f]
		(swap! state-ref f))
	(reset-state! [_ new-state]
		(reset! state-ref new-state)))

(defn atom-registry
	[initial-state]
	(->AtomRegistry (atom initial-state)))

(defn lookup
	([registry k]
	 (get (snapshot registry) k))
	([registry k default]
	 (get (snapshot registry) k default)))

(defn lookup-in
	[registry path]
	(get-in (snapshot registry) path))