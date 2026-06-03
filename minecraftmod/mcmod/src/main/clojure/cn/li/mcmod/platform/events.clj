(ns cn.li.mcmod.platform.events
  "Platform-neutral event bridge."
  (:require [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *fire-event-fn* nil)

(defn install-fire-event-fn!
  [fire-fn label]
  (prt/install-impl! #'*fire-event-fn* fire-fn (or label "platform-events")))

(defn available? [] (prt/impl-available? #'*fire-event-fn*))
(defn current [] (prt/impl-current #'*fire-event-fn*))
(defn call-with-runtime [fire-fn f] (binding [*fire-event-fn* fire-fn] (f)))

(defn fire-event!
  "Post an event object to the platform event bus. No-op when not installed."
  [event]
  (when-let [fire *fire-event-fn*]
    (when event
      (try
        (fire event)
        (catch Exception e
          (log/warn "Event dispatch failed:" (ex-message e)))))))
