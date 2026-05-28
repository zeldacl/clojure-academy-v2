(ns cn.li.forge1201.setup.common
	"Forge common-setup wiring extracted from mod entry.

	Keeps mod namespace focused on registration/bootstrap while this namespace owns
	common setup side effects and event subscriptions."
	(:require [cn.li.forge1201.gui.init :as gui-init]
						[cn.li.forge1201.runtime.lifecycle :as runtime-lifecycle]
						[cn.li.forge1201.integration.forge-energy :as forge-energy]
						[cn.li.forge1201.integration.ic2-energy :as ic2-energy]
						[cn.li.forge1201.runtime.item-handler :as runtime-item-handler]
						[cn.li.forge1201.integration.imc-dispatch :as imc-dispatch]
						[cn.li.forge1201.setup.event-registration :as event-registration]
						[cn.li.mcmod.util.log :as log]))

(def ^:private common-setup-guard-lock
	(Object.))

(def ^:private ^:dynamic *common-setup-ran?*
	false)

(defn run-common-setup!
	[]
	(if (var-get #'*common-setup-ran?*)
		(log/info "Forge common setup wiring already complete; skipping duplicate invocation")
		(locking common-setup-guard-lock
			(if (var-get #'*common-setup-ran?*)
				(log/info "Forge common setup wiring already complete; skipping duplicate invocation")
				(do
					(gui-init/init-common!)
					(runtime-lifecycle/init-common!)
					(forge-energy/init-forge-energy!)
					(ic2-energy/init-ic2-energy!)
					(runtime-item-handler/init!)
					(imc-dispatch/init!)
					(event-registration/register-common-event-listeners!)
					(alter-var-root #'*common-setup-ran?* (constantly true))
					(log/info "Forge common setup wiring complete"))))))