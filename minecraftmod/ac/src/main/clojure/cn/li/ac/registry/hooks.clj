(ns cn.li.ac.registry.hooks
  "Hook registry system for explicit network handler and client renderer setup.

  Blocks/items/GUIs should register hooks from explicit init functions. The AC
  lifecycle invokes the collected hooks during initialization/client setup."
  (:require [cn.li.mcmod.util.log :as log]))

;; Unified hook registry state.
(defonce hook-registry
  (atom {:network-handlers []
         :client-renderers []}))

(defn- dedupe-conj
  [items item]
  (if (some #(= % item) items)
    items
    (conj items item)))

(defn get-network-handlers
  "Return currently registered network handler init fns."
  []
  (:network-handlers @hook-registry))

(defn get-client-renderers
  "Return currently registered client renderer init symbols."
  []
  (:client-renderers @hook-registry))

(defn reset-registries!
  "Reset both registries to an empty state.
  Primarily used by tests and REPL workflows."
  []
  (reset! hook-registry {:network-handlers []
                         :client-renderers []}))

(defn register-network-handler!
  "Register a network handler registration function.
  Called by block/GUI init functions to register their handlers."
  [handler-fn]
  (swap! hook-registry update :network-handlers dedupe-conj handler-fn))

(defn register-client-renderer!
  "Register a client renderer namespace symbol.
  Called by block init functions to register their renderers."
  [renderer-ns-sym]
  (swap! hook-registry update :client-renderers dedupe-conj renderer-ns-sym))

(defn call-all-network-handlers-with-report!
  "Call all network handler registration functions and collect result details.
  Returns {:ok [...], :failed [...]} where each failed item includes
  {:handler <fn> :error <message>}"
  []
  (reduce (fn [{:keys [ok failed] :as acc} handler]
            (try
              (handler)
              (assoc acc :ok (conj ok handler))
              (catch Throwable t
                (assoc acc :failed (conj failed {:handler handler
                                                 :error (ex-message t)})))))
          {:ok [] :failed []}
          (get-network-handlers)))

(defn call-all-network-handlers!
  "Call all registered network handler registration functions.
  Called by core.clj during initialization."
  []
  (let [{:keys [failed] :as report} (call-all-network-handlers-with-report!)]
    (when (seq failed)
      (log/error "Network handler registration failed:" failed)
      (throw (ex-info "Network handler registration failed"
                      {:report report})))
    nil))

(defn load-all-client-renderers-with-report!
  "Load all client renderer namespaces and collect result details.
  Returns {:ok [...], :failed [...]} where failed items include
  {:renderer <symbol> :error <message>}"
  []
  (reduce (fn [{:keys [ok failed] :as acc} renderer-ns]
            (try
              (if-let [init-fn (requiring-resolve renderer-ns)]
                (do
                  (init-fn)
                  (assoc acc :ok (conj ok renderer-ns)))
                (assoc acc :failed (conj failed {:renderer renderer-ns
                                                 :error "init function not found"})))
              (catch Throwable t
                (assoc acc :failed (conj failed {:renderer renderer-ns
                                                 :error (ex-message t)})))))
          {:ok [] :failed []}
          (get-client-renderers)))

(defn load-all-client-renderers!
  "Load all registered client renderer namespaces.
  Called by core.clj during client initialization.
  Uses requiring-resolve to safely load client-only code."
  []
  (let [{:keys [failed] :as report} (load-all-client-renderers-with-report!)]
    (when (seq failed)
      (log/error "Client renderer loading failed:" failed)
      (throw (ex-info "Client renderer loading failed"
                      {:report report})))
    nil))
