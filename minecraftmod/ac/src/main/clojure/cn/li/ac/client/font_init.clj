(ns cn.li.ac.client.font-init
	"Register AC CGui font keywords for MSDF shadow font.

	This namespace intentionally avoids static compile-time dependencies on
	mc1201/Minecraft classes. Runtime registration is delegated through
	`cn.li.mc1201.gui.cgui.font/register-font!` when available."
	(:require [cn.li.mcmod.util.log :as log]
	          [cn.li.mcmod.client.platform-bridge :as platform-bridge]))

(defonce ^:private fonts-registered? (atom false))

(defn- register-ac-fonts!
	[]
	(platform-bridge/register-font! :ac-normal {})
	(platform-bridge/register-font! :ac-bold {:bold? true})
	(platform-bridge/register-font! :ac-italic {:italic? true}))

(defn init-fonts!
	"Register :ac-normal / :ac-bold / :ac-italic for CGui.
	Idempotent — safe to call multiple times (the content client-init hook can
	fire before the platform bridge is installed; the platform retries after
	its bridge merge via the :client-font-init! hook). Only marks success on
	actual registration so a too-early failure can retry."
	[]
	(when-not @fonts-registered?
	  (try
	    (register-ac-fonts!)
	    (reset! fonts-registered? true)
	    (log/info "AC MSDF font keywords registered (:ac-normal, :ac-bold, :ac-italic)")
	    (catch Exception e
	      (log/error "Failed to initialize AC fonts:" (ex-message e))))))
