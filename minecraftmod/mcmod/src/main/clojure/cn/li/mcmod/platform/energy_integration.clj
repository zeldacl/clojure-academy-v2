(ns cn.li.mcmod.platform.energy-integration
	"Platform-neutral bridge for energy integration settings.")

(defn- default-energy-integration-state []
	{:forge-energy-conversion-rate (fn [] 1.0)
	 :ic2-energy-conversion-rate (fn [] 1.0)})

(defn create-energy-integration-runtime
	([] (create-energy-integration-runtime {}))
	([{:keys [state*]}]
	 {:cn.li.mcmod.platform.energy-integration/runtime ::energy-integration-runtime
	  :state* (or state* (atom (default-energy-integration-state)))}))

(defonce ^:private installed-energy-integration-runtime
	(create-energy-integration-runtime))

(defonce ^:private energy-integration-runtime-override* (atom nil))

(defn- energy-hooks-atom []
	(:state* (or @energy-integration-runtime-override* installed-energy-integration-runtime)))

(defn- energy-hooks-snapshot []
	@(energy-hooks-atom))

(defn register-energy-integration-hooks!
	[hooks]
	(swap! (energy-hooks-atom) merge hooks)
	nil)

(defn forge-energy-conversion-rate
	[]
	((:forge-energy-conversion-rate (energy-hooks-snapshot))))

(defn ic2-energy-conversion-rate
	[]
	((:ic2-energy-conversion-rate (energy-hooks-snapshot))))