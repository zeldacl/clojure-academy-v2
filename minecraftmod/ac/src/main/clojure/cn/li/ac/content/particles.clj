(ns cn.li.ac.content.particles
	"Custom particle declarations."
	(:require [cn.li.mcmod.particle.dsl :as pdsl]
						[cn.li.mcmod.util.log :as log]))

(defonce particles-initialized? (atom false))

(defn init-particles!
	[]
	(when (compare-and-set! particles-initialized? false true)
		(pdsl/defparticle {:id "electric_arc"})
		(pdsl/defparticle {:id "teleport_ripple"})
		(pdsl/defparticle {:id "melt_glow"})
		(log/info "Particle content initialized")))