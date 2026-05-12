(ns cn.li.forge1201.runtime.install
	"Forge runtime adapter installer.

	Centralizes runtime adapter installation so entry/lifecycle namespaces stay focused
	on event wiring only."
	(:require [cn.li.forge1201.runtime.adapters.install :as adapters-install]))

(defn install-runtime-adapters!
	[]
	(adapters-install/install-adapters!))
