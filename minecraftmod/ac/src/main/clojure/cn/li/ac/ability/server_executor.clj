(ns cn.li.ac.ability.server-executor
	"Phase C server-side execution facade for ability runtime.

	Bridges registry skill specs, lifecycle SPI hooks, and the existing pattern
	dispatch engine behind a single entrypoint namespace."
	(:require [cn.li.ac.ability.server.dispatch :as dispatch]
						[cn.li.ac.ability.spi-lifecycle :as lifecycle]))

(def can-handle? dispatch/can-handle?)
(def dispatch! dispatch/dispatch!)

(defn execute-skill-event!
	"Execute a server-side ability callback.

	Lifecycle hooks are emitted when a skill id is available, then the existing
	dispatch engine receives the event for pattern-based skills. Returns true
	when the runtime handled the event, else nil/false."
	[skill-spec player context callback-key event]
	(let [skill-id (:id skill-spec)
				lifecycle-context (merge {:skill-id skill-id
																	:skill-spec skill-spec
																	:callback-key callback-key}
																 (or context {})
																 {:event event})]
		(when (or (nil? skill-id)
							(lifecycle/ability-can-execute? skill-id player lifecycle-context))
			(case callback-key
				:on-activate (lifecycle/trigger-activate! skill-id player lifecycle-context)
				:on-tick (lifecycle/trigger-tick! skill-id player lifecycle-context)
				:on-deactivate (lifecycle/trigger-deactivate! skill-id player lifecycle-context)
				nil)
			(dispatch/dispatch! skill-spec callback-key event))))