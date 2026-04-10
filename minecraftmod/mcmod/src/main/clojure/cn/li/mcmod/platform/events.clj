(ns cn.li.mcmod.platform.events
  "Platform-neutral event bridge.

  Forge binds *fire-event-fn* during platform init. Code in ac can call
  fire-event! without importing any Forge or Minecraft classes.")

(defn- log-warn
  [& xs]
  (when-let [f (requiring-resolve 'cn.li.mcmod.util.log/warn)]
    (apply f xs)))

(def ^:dynamic *fire-event-fn*
  "Bound by the platform (Forge) to (fn [event]).
  Posts the event to the Forge game event bus.
  nil in non-Forge contexts (data-gen, tests, Fabric)."
  nil)

(defn fire-event!
  "Post an event object to the platform event bus.
  No-op when *fire-event-fn* is not bound (e.g. in tests or on Fabric)."
  [event]
  (when (and *fire-event-fn* event)
    (try
      (*fire-event-fn* event)
      (catch Exception e
        (log-warn "Event dispatch failed:" (ex-message e))))))
