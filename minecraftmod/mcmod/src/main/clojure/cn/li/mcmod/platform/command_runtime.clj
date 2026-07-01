(ns cn.li.mcmod.platform.command-runtime
	"Platform-neutral bridge for content-owned command initialization."
	(:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private noop
	(fn [] nil))

(defn- default-command-runtime-state []
	{:init-commands! noop})

(defn create-command-runtime
	([] (create-command-runtime {}))
	([{:keys [state*]}]
	 {:cn.li.mcmod.platform.command-runtime/runtime ::command-runtime
	  :state* (or state* (atom (default-command-runtime-state)))}))

(def ^:private _command-runtime (delay (create-command-runtime)))

(def ^:dynamic *command-runtime* nil)

(defn- command-hooks-atom []
	(:state* (or *command-runtime*
	               @_command-runtime)))

(defn- command-hooks-snapshot []
	@(command-hooks-atom))

(defn register-command-hooks!
	[hooks]
	(doseq [[k v] hooks]
		(prt/register-hook! (command-hooks-atom) k v
		                    :duplicate-policy :same-value-idempotent
		                    :label "command-runtime"))
	nil)

(defn reset-command-hooks-for-test!
	[]
	(reset! (command-hooks-atom) (default-command-runtime-state))
	nil)

(defn init-commands!
	[]
	((:init-commands! (command-hooks-snapshot))))