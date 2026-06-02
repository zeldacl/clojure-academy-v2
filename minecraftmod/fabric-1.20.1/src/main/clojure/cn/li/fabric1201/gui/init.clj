(ns cn.li.fabric1201.gui.init
  "Fabric 1.20.1 GUI System Initialization"
  (:require [cn.li.mc1201.gui.init.orchestrator :as gui-orchestrator]
            [cn.li.mc1201.gui.init.checks :as init-checks]
            [cn.li.mc1201.runtime.spi.gui-registry :as registry-api]
            [cn.li.mcmod.gui.registry :as gui]
            [cn.li.fabric1201.adapter.gui-registry :as registry-impl]
            [cn.li.fabric1201.gui.network.server :as network-server]
            [cn.li.mcmod.util.log :as log]))

(def ^:private platform-label "Fabric 1.20.1")

(defn- optional-init!
  [sym missing-message]
  (if-let [init! (requiring-resolve sym)]
    (init!)
    (log/warn missing-message)))

(def ^:private common-phase
  {:platform-label platform-label
   :phase-label "Common"
   :steps [{:run registry-impl/init!}]})

(def ^:private server-phase
  {:platform-label platform-label
   :phase-label "Server"
   :steps [{:run network-server/init-server!}]})

(def ^:private client-phase
  {:platform-label platform-label
   :phase-label "Client"
   :steps [{:run #(optional-init! 'cn.li.fabric1201.gui.screen-impl/init-client!
                                  "Fabric GUI screen impl not available on current side")}
           {:run #(optional-init! 'cn.li.fabric1201.gui.network.client/init-client!
                                  "Fabric GUI client network not available on current side")}]})

(defn init-common! []
  (gui-orchestrator/run-phase! common-phase))

(defn init-server! []
  (gui-orchestrator/run-phase! server-phase))

(defn init-client! []
  (gui-orchestrator/run-phase! client-phase))

(defn verify-initialization []
  (let [gui-checks (init-checks/build-gui-checks
                     (gui/get-all-gui-ids)
                     "gui-"
                     (fn [gui-id]
                       (some? (registry-impl/get-handler-type gui-id))))
        checks gui-checks]
    (gui-orchestrator/verify-checks! "Verifying Fabric GUI system initialization..." checks)))

(defn cleanup! []
  (log/info "Cleaning up Fabric GUI system")
  (registry-api/invalidate-menu-registry! :fabric-1.20.1)
  (log/info "Fabric GUI system cleanup complete"))
