(ns cn.li.ac.registry.hooks
  "Hook registry system for auto-registration of network handlers and client renderers.

  Blocks/items/GUIs register their hooks during namespace load, then cn.li.ac.core/init
  calls all registered hooks during initialization.")

;; Registry of network handler registration functions.
;; Each entry is a 0-arity function that registers network handlers.
(defonce network-handler-registry (atom []))

;; Registry of client renderer namespace symbols.
;; Each entry is a namespace symbol with an init function.
(defonce client-renderer-registry (atom []))

(defn register-network-handler!
  "Register a network handler registration function.
  Called by block/GUI namespaces during load to register their handlers."
  [handler-fn]
  (swap! network-handler-registry conj handler-fn))

(defn register-client-renderer!
  "Register a client renderer namespace symbol.
  Called by block namespaces during load to register their renderers."
  [renderer-ns-sym]
  (swap! client-renderer-registry conj renderer-ns-sym))

(defn call-all-network-handlers!
  "Call all registered network handler registration functions.
  Called by core.clj during initialization."
  []
  (doseq [handler @network-handler-registry]
    (handler)))

(defn load-all-client-renderers!
  "Load all registered client renderer namespaces.
  Called by core.clj during client initialization.
  Uses requiring-resolve to safely load client-only code."
  []
  (doseq [renderer-ns @client-renderer-registry]
    (when-let [init-fn (requiring-resolve renderer-ns)]
      (init-fn))))
