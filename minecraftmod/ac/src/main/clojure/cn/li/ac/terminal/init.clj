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
    ;; Load all app namespaces, then perform explicit registration.
    (doseq [init-sym '[cn.li.ac.terminal.apps.skill-tree/init-skill-tree-app!
                      cn.li.ac.terminal.apps.settings/init-settings-app!
                      cn.li.ac.terminal.apps.tutorial/init-tutorial-app!
                      cn.li.ac.terminal.apps.freq-transmitter/init-freq-transmitter-app!
                      cn.li.ac.terminal.apps.media-player/init-media-player-app!
                      cn.li.ac.terminal.apps.about/init-about-app!]]
      (when-let [init-fn (requiring-resolve init-sym)]
        (init-fn)))

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
