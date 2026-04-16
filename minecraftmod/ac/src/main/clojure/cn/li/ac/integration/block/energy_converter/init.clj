(ns cn.li.ac.integration.block.energy-converter.init
	"Energy converter integration bootstrap.

	Provides content-loader entry points used by integration content init."
	(:require [cn.li.ac.integration.block.energy-converter.config :as ec-config]
						[cn.li.ac.integration.block.energy-converter.platform-bridge :as ec-bridge]
						[cn.li.mcmod.util.log :as log]))

(defonce ^:private converters-loaded? (atom false))
(defonce ^:private converters-initialized? (atom false))

(defn load-converters!
	[]
	(when (compare-and-set! converters-loaded? false true)
		(ec-bridge/install-energy-integration-hooks!)
		(log/info "Energy converters loaded"
							{:count (count ec-config/supported-blocks)})))

(defn init-converters!
	[]
	(when (compare-and-set! converters-initialized? false true)
		;; Registration wiring placeholder for converter blocks.
		(log/info "Energy converters initialized"
							{:count (count ec-config/supported-blocks)})))