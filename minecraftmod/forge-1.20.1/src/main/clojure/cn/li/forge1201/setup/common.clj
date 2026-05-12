(ns cn.li.forge1201.setup.common
	"Forge common-setup wiring extracted from mod entry.

	Keeps mod namespace focused on registration/bootstrap while this namespace owns
	common setup side effects and event subscriptions."
	(:require [cn.li.forge1201.gui.init :as gui-init]
						[cn.li.forge1201.runtime.lifecycle :as runtime-lifecycle]
						[cn.li.forge1201.config.game-config :as game-config]
						[cn.li.mc1201.config.gameplay-bridge :as shared-gameplay-bridge]
						[cn.li.forge1201.integration.forge-energy :as forge-energy]
						[cn.li.forge1201.integration.ic2-energy :as ic2-energy]
						[cn.li.forge1201.runtime.item-handler :as runtime-item-handler]
						[cn.li.forge1201.integration.imc-dispatch :as imc-dispatch]
						[cn.li.forge1201.setup.event-listeners :as event-listeners]
						[cn.li.mcmod.util.log :as log])
	)

(defn run-common-setup!
	[]
	(gui-init/init-common!)
	(runtime-lifecycle/init-common!)
	(shared-gameplay-bridge/bind-gameplay-config! (game-config/provider-map))
	(forge-energy/init-forge-energy!)
	(ic2-energy/init-ic2-energy!)
	(runtime-item-handler/init!)
	(imc-dispatch/init!)
	(event-listeners/register-common-event-listeners!)
	(log/info "Forge common setup wiring complete"))