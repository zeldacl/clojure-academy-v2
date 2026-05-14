(ns cn.li.mc1201.integration.event-support
  "Shared helpers for cross-platform event handlers.
   
   This namespace is intentionally tiny but useful: it centralizes the repeated
   guarded-execution / logging pattern used by Forge and Fabric event wrappers."
  (:require [cn.li.mcmod.util.log :as log]))

(defn guarded-call
  "Execute thunk and return fallback if it throws.
   
   label is included in the log output so platform adapters can share the same
   error handling style without duplicating boilerplate."
  [label fallback thunk]
  (try
    (thunk)
    (catch Throwable t
      (log/error (str "Error handling " label ":") (.getMessage t))
      (.printStackTrace t)
      fallback)))
