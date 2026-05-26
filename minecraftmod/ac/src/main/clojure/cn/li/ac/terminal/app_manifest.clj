(ns cn.li.ac.terminal.app-manifest
	"Declarative registry for terminal app init symbols.

	This keeps app initialization extensible and testable while preserving
	deterministic order."
	(:require [cn.li.mcmod.util.log :as log]))

(def ^:private default-app-init-symbols
	'[cn.li.ac.terminal.apps.skill-tree/init-skill-tree-app!
		cn.li.ac.terminal.apps.settings/init-settings-app!
		cn.li.ac.terminal.apps.tutorial/init-tutorial-app!
		cn.li.ac.terminal.apps.freq-transmitter/init-freq-transmitter-app!
		cn.li.ac.terminal.apps.media-player/init-media-player-app!
		cn.li.ac.terminal.apps.about/init-about-app!])

(defonce ^:private app-init-registry
	(atom (vec default-app-init-symbols)))

(defonce ^:private app-init-registry-frozen? (atom false))

(defn- assert-app-init-registry-open!
	[]
	(when @app-init-registry-frozen?
		(throw (ex-info "Terminal app init registry is frozen" {}))))

(defn app-init-registry-snapshot
	[]
	{:init-symbols @app-init-registry
	 :frozen? @app-init-registry-frozen?})

(defn reset-app-init-registry-for-test!
	([]
	 (reset-app-init-registry-for-test! {:init-symbols (vec default-app-init-symbols)}))
	([{:keys [init-symbols frozen?]
		 :or {init-symbols (vec default-app-init-symbols) frozen? false}}]
	 (reset! app-init-registry (vec init-symbols))
	 (reset! app-init-registry-frozen? frozen?)
	 nil))

(defn freeze-app-init-registry!
	[]
	(reset! app-init-registry-frozen? true)
	nil)

(defn list-app-init-symbols
	"Get terminal app init symbols in registration order."
	[]
	@app-init-registry)

(defn register-app-init!
	"Register one terminal app init symbol if absent."
	[init-sym]
	{:pre [(symbol? init-sym)]}
	(assert-app-init-registry-open!)
	(swap! app-init-registry
				 (fn [items]
					 (if (some #(= % init-sym) items)
						 items
						 (conj items init-sym))))
	(log/debug "Registered terminal app init symbol" init-sym)
	init-sym)

(defn set-app-init-symbols!
	"Replace app init symbols with provided ordered collection.
	Used by tests and controlled bootstrap flows."
	[init-syms]
	(let [normalized (vec init-syms)]
		(when-not (every? symbol? normalized)
			(throw (ex-info "All terminal app init entries must be symbols"
											{:entries init-syms})))
		(assert-app-init-registry-open!)
		(reset! app-init-registry normalized)))

(defn reset-defaults!
	"Reset app init symbols to built-in defaults."
	[]
	(reset-app-init-registry-for-test!))