(ns cn.li.mcmod.protocol.core
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

(deftype VarRootRegistry [state-var lock]
	IRegistry
	(snapshot [_]
		(var-get state-var))
	(swap-state! [_ f]
		(locking lock
			(let [next-state (f (var-get state-var))]
				(alter-var-root state-var (constantly next-state))
				next-state)))
	(reset-state! [_ new-state]
		(locking lock
			(alter-var-root state-var (constantly new-state))
			new-state)))

(defn atom-registry
	[initial-state]
	(->AtomRegistry (atom initial-state)))

(defn var-root-registry
	[state-var]
	(->VarRootRegistry state-var (Object.)))

(defn lookup
	([registry k]
	 (get (snapshot registry) k))
	([registry k default]
	 (get (snapshot registry) k default)))

(defn lookup-in
	[registry path]
	(get-in (snapshot registry) path))
