(ns cn.li.ac.command.platform-bridge
	"AC command bindings for the platform-neutral command bridge."
	(:require [cn.li.mcmod.platform.command-runtime :as command-runtime]
						[cn.li.ac.command.commands :as commands]
						[cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))

(defn install-command-hooks!
	[]
	(when (compare-and-set! hooks-installed? false true)
		(command-runtime/register-command-hooks!
			{:init-commands! commands/init-commands!})
		(log/info "AC command hooks installed"))
	nil)