(ns cn.li.ac.terminal.init
  "Terminal system initialization.

  Handles:
  - App registration
  - Network handler registration
  - System initialization"
  (:require [cn.li.ac.terminal.app-registry :as app-reg]
            [cn.li.ac.terminal.app-manifest :as app-manifest]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Registration
;; ============================================================================

(defn register-apps!
  "Load and register all terminal apps."
  []
  (let [init-symbols (app-manifest/list-app-init-symbols)
        failures (atom [])]
    ;; Load all app namespaces, then perform explicit registration.
    (doseq [init-sym init-symbols]
      (try
        (if-let [init-fn (requiring-resolve init-sym)]
          (init-fn)
          (swap! failures conj {:symbol init-sym
                                :error "init function not found"}))
        (catch Throwable t
          (swap! failures conj {:symbol init-sym
                                :error (ex-message t)}))))

    (if (seq @failures)
      (log/error "Terminal app registration completed with failures" @failures)
      (log/info "Registered" (app-reg/app-count) "terminal apps"))))

;; ============================================================================
;; Network Registration
;; ============================================================================

(defn register-network-handlers!
  "Register terminal network handlers."
  []
  (when-let [register-handlers! (requiring-resolve 'cn.li.ac.terminal.network/register-handlers!)]
    (register-handlers!)))

;; ============================================================================
;; System Initialization
;; ============================================================================

(defn init-terminal!
  "Initialize the terminal system."
  []
  (log/info "Initializing terminal system...")

  ;; Register apps
  (register-apps!)

  ;; Register network handlers via hook system
  (hooks/register-network-handler! register-network-handlers!)

  (log/info "Terminal system initialized successfully"))
