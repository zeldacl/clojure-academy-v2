(ns cn.li.ac.client.font-init
	"Register AC CGui font keywords for MSDF shadow font.

	This namespace intentionally avoids static compile-time dependencies on
	mc1201/Minecraft classes. Runtime registration is delegated through
	`cn.li.mc1201.gui.cgui.font/register-font!` when available."
	(:require [cn.li.mcmod.util.log :as log]
	          [cn.li.mcmod.client.platform-bridge :as platform-bridge]))

(defn- register-ac-fonts!
	[]
	(platform-bridge/register-font! :ac-normal {})
	(platform-bridge/register-font! :ac-bold {:bold? true})
	(platform-bridge/register-font! :ac-italic {:italic? true}))

(defn init-fonts!
	"Register :ac-normal / :ac-bold / :ac-italic for CGui.
	Idempotent — safe to call multiple times."
	[]
	(try
		(register-ac-fonts!)
		(log/info "AC MSDF font keywords registered (:ac-normal, :ac-bold, :ac-italic)")
		(catch Exception e
			(log/error "Failed to initialize AC fonts:" (ex-message e)))))
