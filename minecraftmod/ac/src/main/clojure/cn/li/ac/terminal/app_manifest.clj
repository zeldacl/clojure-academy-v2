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

(defonce app-init-registry
	(atom (vec default-app-init-symbols)))

(defn list-app-init-symbols
	"Get terminal app init symbols in registration order."
	[]
	@app-init-registry)

(defn register-app-init!
	"Register one terminal app init symbol if absent."
	[init-sym]
	{:pre [(symbol? init-sym)]}
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
		(reset! app-init-registry normalized)))

(defn reset-defaults!
	"Reset app init symbols to built-in defaults."
	[]
	(reset! app-init-registry (vec default-app-init-symbols)))