(ns cn.li.mcmod.platform.command-runtime
	"Platform-neutral bridge for content-owned command initialization.")

(def ^:private noop
	(fn [] nil))

(defonce ^:private command-hooks
	(atom {:init-commands! noop}))

(defn register-command-hooks!
	[hooks]
	(swap! command-hooks merge hooks)
	nil)

(defn init-commands!
	[]
	((:init-commands! @command-hooks)))