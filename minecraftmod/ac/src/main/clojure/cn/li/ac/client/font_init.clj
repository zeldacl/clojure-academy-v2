(ns cn.li.ac.client.font-init
	"Register AC CGui font keywords for MSDF shadow font.

	This namespace intentionally avoids static compile-time dependencies on
	mc1201/Minecraft classes. Runtime registration is delegated through
	`cn.li.mc1201.gui.cgui.font/register-font!` when available."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private fonts-initialized? (atom false))

(defn- register-ac-fonts!
	[]
	(when-let [register-font! (requiring-resolve 'cn.li.mc1201.gui.cgui.font/register-font!)]
		(register-font! :ac-normal {})
		(register-font! :ac-bold {:bold? true})
		(register-font! :ac-italic {:italic? true})))

(defn init-fonts!
	"Register :ac-normal / :ac-bold / :ac-italic for CGui."
	[]
	(when (compare-and-set! fonts-initialized? false true)
		(try
			(register-ac-fonts!)
			(log/info "AC MSDF font keywords registered (:ac-normal, :ac-bold, :ac-italic)")
			(catch Exception e
				(log/error "Failed to initialize AC fonts:" (ex-message e))))))
