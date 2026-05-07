(ns cn.li.fabric1201.gui.init
  "Fabric 1.20.1 GUI System Initialization"
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.fabric1201.gui.registry-impl :as registry-impl]
            [cn.li.fabric1201.gui.network :as network]
            [cn.li.mcmod.util.log :as log]))

(defn init-common! []
  (log/info "=== Initializing Fabric 1.20.1 GUI System (Common) ===")
  (registry-impl/init!)
  (log/info "=== Fabric 1.20.1 GUI System (Common) Initialized ==="))

(defn init-server! []
  (log/info "=== Initializing Fabric 1.20.1 GUI System (Server) ===")
  (network/init-server!)
  (log/info "=== Fabric 1.20.1 GUI System (Server) Initialized ==="))

(defn init-client! []
  (log/info "=== Initializing Fabric 1.20.1 GUI System (Client) ===")
  (if-let [init-screen! (requiring-resolve 'cn.li.fabric1201.gui.screen-impl/init-client!)]
    (init-screen!)
    (log/warn "Fabric GUI screen impl not available on current side"))
  (network/init-client!)
  (log/info "=== Fabric 1.20.1 GUI System (Client) Initialized ==="))

(defn verify-initialization []
  (log/info "Verifying Fabric GUI system initialization...")
  (let [gui-checks (into {}
                        (for [gui-id (gui/get-all-gui-ids)]
                          (let [check-key (keyword (str "gui-" gui-id "-handler-type"))
                                handler-type (registry-impl/get-handler-type gui-id)]
                            [check-key (some? handler-type)])))
        checks gui-checks]
    (doseq [[check-name result] checks]
      (log/info "  " check-name ":" (if result "✓" "✗")))
    (let [all-passed? (every? true? (vals checks))]
      (if all-passed?
        (log/info "All Fabric GUI system checks passed!")
        (log/error "Some Fabric GUI system checks failed!"))
      all-passed?)))

(defn safe-init-common! []
  (try (init-common!) true
       (catch Exception e
         (log/error "Failed to initialize common Fabric GUI system:" (.getMessage e))
         (.printStackTrace e)
         false)))

(defn safe-init-client! []
  (try (init-client!) true
       (catch Exception e
         (log/error "Failed to initialize client Fabric GUI system:" (.getMessage e))
         (.printStackTrace e)
         false)))

(defn safe-init-server! []
  (try (init-server!) true
       (catch Exception e
         (log/error "Failed to initialize server Fabric GUI system:" (.getMessage e))
         (.printStackTrace e)
         false)))

(defn register-with-fabric-api! []
  (log/info "Registering with Fabric API events")
  (log/info "Fabric API event registration complete"))

(defn init-all! []
  (log/info "=== Full Fabric 1.20.1 GUI Initialization ===")
  (safe-init-common!)
  (safe-init-server!)
  (safe-init-client!)
  (verify-initialization)
  (log/info "=== Full Fabric 1.20.1 GUI Initialization Complete ==="))

(defn cleanup! []
  (log/info "Cleaning up Fabric GUI system")
  (reset! registry-impl/gui-handler-types {})
  (log/info "Fabric GUI system cleanup complete"))
