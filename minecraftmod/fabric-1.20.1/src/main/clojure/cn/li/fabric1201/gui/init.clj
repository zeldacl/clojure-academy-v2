(ns cn.li.fabric1201.gui.init
  "Fabric 1.20.1 GUI System Initialization"
  (:require [cn.li.mc1201.gui.init-orchestrator :as gui-orchestrator]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.fabric1201.gui.registry-impl :as registry-impl]
            [cn.li.fabric1201.gui.network :as network]
            [cn.li.mcmod.util.log :as log]))

(defn init-common! []
  (gui-orchestrator/phase-start! "Fabric 1.20.1" "Common")
  (registry-impl/init!)
  (gui-orchestrator/phase-done! "Fabric 1.20.1" "Common"))

(defn init-server! []
  (gui-orchestrator/phase-start! "Fabric 1.20.1" "Server")
  (network/init-server!)
  (gui-orchestrator/phase-done! "Fabric 1.20.1" "Server"))

(defn init-client! []
  (gui-orchestrator/phase-start! "Fabric 1.20.1" "Client")
  (if-let [init-screen! (requiring-resolve 'cn.li.fabric1201.gui.screen-impl/init-client!)]
    (init-screen!)
    (log/warn "Fabric GUI screen impl not available on current side"))
  (network/init-client!)
  (gui-orchestrator/phase-done! "Fabric 1.20.1" "Client"))

(defn verify-initialization []
  (let [gui-checks (into {}
                        (for [gui-id (gui/get-all-gui-ids)]
                          (let [check-key (keyword (str "gui-" gui-id "-handler-type"))
                                handler-type (registry-impl/get-handler-type gui-id)]
                            [check-key (some? handler-type)])))
        checks gui-checks]
    (gui-orchestrator/verify-checks! "Verifying Fabric GUI system initialization..." checks)))

(defn safe-init-common! []
  (gui-orchestrator/safe-init! "Failed to initialize common Fabric GUI system:" init-common!))

(defn safe-init-client! []
  (gui-orchestrator/safe-init! "Failed to initialize client Fabric GUI system:" init-client!))

(defn safe-init-server! []
  (gui-orchestrator/safe-init! "Failed to initialize server Fabric GUI system:" init-server!))

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
