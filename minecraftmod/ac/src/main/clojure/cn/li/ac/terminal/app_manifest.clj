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

(defn default-app-init-registry-runtime-state
	[]
	{:init-symbols (vec default-app-init-symbols)
	 :frozen? false})

(defn create-app-init-registry-runtime
	([] (create-app-init-registry-runtime {}))
	([{:keys [state*]
		 :or {state* (atom (default-app-init-registry-runtime-state))}}]
	 {::runtime ::app-init-registry-runtime
	  :state* state*}))

(def ^:dynamic *app-init-registry-runtime* nil)

(defonce ^:private installed-app-init-registry-runtime
	(create-app-init-registry-runtime))

(defn call-with-app-init-registry-runtime
	[runtime f]
	(when-not (and (map? runtime)
	               (= ::app-init-registry-runtime (::runtime runtime))
	               (some? (:state* runtime)))
		(throw (ex-info "Expected app init registry runtime" {:runtime runtime})))
	(binding [*app-init-registry-runtime* runtime]
		(f)))

(defn- current-app-init-registry-runtime
	[]
	(or *app-init-registry-runtime*
		installed-app-init-registry-runtime))

(defn- app-init-registry-state-atom
	[]
	(:state* (current-app-init-registry-runtime)))

(defn- app-init-registry-state-snapshot
	[]
	@(app-init-registry-state-atom))

(defn- update-app-init-registry-state!
	[f & args]
	(apply swap! (app-init-registry-state-atom) f args))

(defn- assert-app-init-registry-open!
	[]
	(when (:frozen? (app-init-registry-state-snapshot))
		(throw (ex-info "Terminal app init registry is frozen" {}))))

(defn app-init-registry-snapshot
	[]
	(app-init-registry-state-snapshot))

(defn reset-app-init-registry-for-test!
	([] (reset-app-init-registry-for-test! {:init-symbols (vec default-app-init-symbols)}))
	([{:keys [init-symbols frozen?]
		 :or {init-symbols (vec default-app-init-symbols) frozen? false}}]
	 (reset! (app-init-registry-state-atom)
	         {:init-symbols (vec init-symbols)
	          :frozen? frozen?})
	 nil))

(defn freeze-app-init-registry!
	[]
	(update-app-init-registry-state! assoc :frozen? true)
	nil)

(defn list-app-init-symbols
	"Get terminal app init symbols in registration order."
	[]
	(:init-symbols (app-init-registry-state-snapshot)))

(defn register-app-init!
	"Register one terminal app init symbol if absent."
	[init-sym]
	{:pre [(symbol? init-sym)]}
	(assert-app-init-registry-open!)
	(update-app-init-registry-state!
		update :init-symbols
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
		(update-app-init-registry-state! assoc :init-symbols normalized)))

(defn reset-defaults!
	"Reset app init symbols to built-in defaults."
	[]
	(reset-app-init-registry-for-test!))