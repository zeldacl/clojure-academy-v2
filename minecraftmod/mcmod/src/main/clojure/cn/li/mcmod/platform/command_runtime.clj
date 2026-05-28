(ns cn.li.mcmod.platform.command-runtime
	"Platform-neutral bridge for content-owned command initialization.")

(def ^:private noop
	(fn [] nil))

(defn- default-command-runtime-state []
	{:init-commands! noop})

(defn create-command-runtime
	([] (create-command-runtime {}))
	([{:keys [state*]}]
	 {:cn.li.mcmod.platform.command-runtime/runtime ::command-runtime
	  :state* (or state* (atom (default-command-runtime-state)))}))

(def ^:dynamic *command-runtime* nil)

(defonce ^:private installed-command-runtime
	(create-command-runtime))

(defn- command-hooks-atom []
	(:state* (or *command-runtime* installed-command-runtime)))

(defn- command-hooks-snapshot []
	@(command-hooks-atom))

(defn register-command-hooks!
	[hooks]
	(swap! (command-hooks-atom) merge hooks)
	nil)

(defn init-commands!
	[]
	((:init-commands! (command-hooks-snapshot))))