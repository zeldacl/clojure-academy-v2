(ns cn.li.ac.integration.block.energy-converter.init
	"Energy converter integration bootstrap.

	Provides content-loader entry points used by integration content init."
	(:require [cn.li.ac.integration.block.energy-converter.config :as ec-config]
	          [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.ac.integration.block.energy-converter.platform-bridge :as ec-bridge]
						[cn.li.mcmod.util.log :as log]))

(defonce-guard converters-loaded?)
(defonce-guard converters-initialized?)

(defn load-converters!
	[]
	(with-init-guard converters-loaded?
		(ec-bridge/install-energy-integration-hooks!)
		(log/info "Energy converters loaded"
							{:count (count ec-config/supported-blocks)})))

(defn init-converters!
	[]
	(with-init-guard converters-initialized?
		;; Registration wiring placeholder for converter blocks.
		(log/info "Energy converters initialized"
							{:count (count ec-config/supported-blocks)})))