(ns cn.li.mcmod.platform.energy-integration
	"Platform-neutral bridge for energy integration settings.")

(defonce ^:private energy-hooks
	(atom {:forge-energy-conversion-rate (fn [] 1.0)
	       :ic2-energy-conversion-rate (fn [] 1.0)}))

(defn register-energy-integration-hooks!
	[hooks]
	(swap! energy-hooks merge hooks)
	nil)

(defn forge-energy-conversion-rate
	[]
	((:forge-energy-conversion-rate @energy-hooks)))

(defn ic2-energy-conversion-rate
	[]
	((:ic2-energy-conversion-rate @energy-hooks)))