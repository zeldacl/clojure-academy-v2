(ns cn.li.ac.command.platform-bridge
	"AC command bindings for the platform-neutral command bridge."
	(:require [cn.li.mcmod.platform.command-runtime :as command-runtime]
						[cn.li.mcmod.command.metadata :as command-metadata]
						[cn.li.ac.command.actions :as command-actions]
						[cn.li.ac.command.commands :as commands]
						[cn.li.ac.command.dsl :as command-dsl]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(defn install-command-hooks!
	[]
	(with-init-guard hooks-installed?
		(command-actions/install-command-actions!)
		(command-runtime/register-command-hooks!
			{:init-commands! commands/init-commands!})
		(command-metadata/register-command-registry! (:commands (command-dsl/command-registry-snapshot)))
		(log/info "AC command hooks installed"))
	nil)