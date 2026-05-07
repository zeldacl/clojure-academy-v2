(ns cn.li.ac.content.particles
	"Custom particle declarations."
	(:require [cn.li.mcmod.particle.dsl :as pdsl]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.mcmod.util.log :as log]))

(defonce-guard particles-initialized?)

(defn init-particles!
	[]
	(with-init-guard particles-initialized?
		(pdsl/defparticle {:id "electric_arc"})
		(pdsl/defparticle {:id "teleport_ripple"})
		(pdsl/defparticle {:id "melt_glow"})
		(log/info "Particle content initialized")))