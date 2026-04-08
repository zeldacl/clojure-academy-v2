(ns cn.li.ac.terminal.init
  "Terminal system initialization.

  Handles:
  - App registration
  - Network handler registration
  - System initialization"
  (:require [cn.li.ac.terminal.app-registry :as app-reg]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Registration
;; ============================================================================

(defn register-apps!
  "Load and register all terminal apps."
  []
  (try
    ;; Load all app namespaces (they self-register via register-app!)
    (require 'cn.li.ac.terminal.apps.skill-tree)
    (require 'cn.li.ac.terminal.apps.settings)
    (require 'cn.li.ac.terminal.apps.tutorial)
    (require 'cn.li.ac.terminal.apps.freq-transmitter)
    (require 'cn.li.ac.terminal.apps.about)

    (log/info "Registered" (app-reg/app-count) "terminal apps")
    (catch Exception e
      (log/error "Error registering terminal apps:" (ex-message e)))))

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
