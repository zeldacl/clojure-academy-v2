(ns cn.li.forge1201.gui.init
  "Forge 1.20.1 GUI System Initialization"
  (:require [cn.li.mc1201.gui.init-orchestrator :as gui-orchestrator]
            [cn.li.mc1201.gui.init-checks :as init-checks]
            [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.util.log :as log]))

(def ^:private platform-label "Forge 1.20.1")

(defn- optional-init!
  [sym missing-message]
  (if-let [init! (requiring-resolve sym)]
    (init!)
    (log/warn missing-message)))

(def ^:private common-phase
  {:platform-label platform-label
   :phase-label "Common"
   :steps [{:run #(optional-init! 'cn.li.forge1201.gui.network/init!
                                  "Forge GUI network init fn not available")}]})

(def ^:private client-phase
  {:platform-label platform-label
   :phase-label "Client"
   :steps [{:run #(optional-init! 'cn.li.forge1201.gui.screen-impl/init-client!
                                  "Forge GUI screen impl not available on current side")}]})

(def ^:private server-phase
  {:platform-label platform-label
   :phase-label "Server"
   :steps []})

(defn init-common!
  "Initialize common GUI system (server + client).
  MenuType registration is handled earlier via DeferredRegister during Forge bootstrap;
  only non-registry setup belongs here."
  []
  (gui-orchestrator/run-phase! common-phase))

;; ============================================================================
;; Client-Only Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during FMLClientSetupEvent"
  []
  (gui-orchestrator/run-phase! client-phase))

;; ============================================================================
;; Server-Only Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side GUI system
  
  Should be called during FMLDedicatedServerSetupEvent"
  []
  (gui-orchestrator/run-phase! server-phase))

;; ============================================================================
;; Verification
;; ============================================================================

(defn verify-initialization
  "Verify that GUI system is properly initialized
  
  Platform-agnostic design: Dynamically verifies all GUI IDs from metadata.
  
  Returns: boolean (true if all checks pass)"
  []
  (let [get-menu-type (requiring-resolve 'cn.li.forge1201.adapter.gui-registry/get-menu-type)
        checks (init-checks/build-gui-checks
                 (gui/get-all-gui-ids)
                 "gui-"
                 (fn [gui-id]
                   (let [menu-type (when get-menu-type (get-menu-type gui-id))]
                     (some? menu-type))))]
    (gui-orchestrator/verify-checks! "Verifying GUI system initialization..." checks)))

;; ============================================================================
;; Error Recovery
;; ============================================================================

(defn safe-init-common!
  "Initialize common GUI system with error handling"
  []
  (gui-orchestrator/safe-run-phase! common-phase))

(defn safe-init-client!
  "Initialize client GUI system with error handling"
  []
  (gui-orchestrator/safe-run-phase! client-phase))

(defn safe-init-server!
  "Initialize server GUI system with error handling"
  []
  (gui-orchestrator/safe-run-phase! server-phase))
