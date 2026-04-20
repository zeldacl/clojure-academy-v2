(ns cn.li.ac.content.sounds
	"Sound event declarations for AC content."
	(:require [cn.li.mcmod.sound.dsl :as sdsl]
						[cn.li.mcmod.util.log :as log]))

(defonce sounds-initialized? (atom false))

(defn init-sounds!
	[]
	(when (compare-and-set! sounds-initialized? false true)
		;; Electromaster sounds
		(sdsl/defsound {:id "em.arc_strong"})
		(sdsl/defsound {:id "em.railgun"})
		(sdsl/defsound {:id "em.charge_loop"})
		(sdsl/defsound {:id "em.intensify_activate"})
		(sdsl/defsound {:id "em.intensify_loop"})
		(sdsl/defsound {:id "em.lf_loop"})
		(sdsl/defsound {:id "em.mag_manip"})

		;; Teleporter sounds
		(sdsl/defsound {:id "tp.tp"})
		(sdsl/defsound {:id "tp.tp_pre"})
		(sdsl/defsound {:id "tp.tp_shift"})
		(sdsl/defsound {:id "tp.tp_flashing"})
		(sdsl/defsound {:id "tp.guts"})

		(log/info "Sound content initialized")))