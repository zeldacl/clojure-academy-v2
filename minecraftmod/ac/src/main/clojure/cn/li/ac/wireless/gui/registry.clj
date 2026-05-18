(ns cn.li.ac.wireless.gui.registry
  "Wireless GUI registration and opening system"
  (:require [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; GUI Handler Protocol + record implementation moved to mcmod
;; ============================================================================

;; ============================================================================
;; GUI Handler Implementation
;; ============================================================================

(defn- registration-value [cfg k]
  (get-in cfg [:registration k]))

(defn- lifecycle-value [cfg k]
  (get-in cfg [:lifecycle k]))

(defn- sync-value [cfg k]
  (get-in cfg [:sync k]))

(defn get-config-by-container
  "Get GUI config by container structure.

  This is used for ticking and syncing all active containers without
  platform-specific knowledge of GUI kinds."
  [container]
  (gui-registry/get-config-by-container container))

;; GUI handler protocol/record implementation moved to mcmod/gui/handler.

;; ============================================================================
;; Global Handler Instance
;; ============================================================================

(def get-gui-handler
  "Get the global GUI handler instance.
   Moved to mcmod/gui/handler."
  gui-handler/get-gui-handler)

;; ============================================================================
;; Registration (for platform-specific implementations)
;; ============================================================================

(defmulti register-gui-handler
  "Register GUI handler with platform-specific system
  
  This should be implemented by platform modules (forge/fabric)"
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [platform-type]
  (log/warn "No GUI handler registration for platform:" platform-type)
  nil)

;; Platform implementations should look like:
;;
;; (defmethod register-gui-handler :forge-1.16.5 [_]
;;   (let [handler (get-gui-handler)]
;;     ;; Register with Forge's NetworkRegistry
;;     ...))
;;
;; (defmethod register-gui-handler :fabric-1.20.1 [_]
;;   (let [handler (get-gui-handler)]
;;     ;; Register with Fabric's ScreenHandlerRegistry
;;     ...))

;; ============================================================================
;; Container Tick Management
;; ============================================================================

(defn tick-all-containers!
  "Tick all active containers (called from server tick event)
  
  This should be called every server tick to update container data"
  []
  (doseq [container (container-state/list-active-containers)]
    (try
      (if-let [cfg (get-config-by-container container)]
        ((lifecycle-value cfg :tick-fn) container)
        (log/warn "Unknown container type:" (type container)))
      (catch Exception e
        (log/error "Error ticking container:" e)))))

;; ============================================================================
;; Network Synchronization
;; ============================================================================

(defn get-container-sync-packet
  "Create a network packet for container data synchronization
  
  Args:
  - container: Container instance
  
  Returns: Map with sync data"
  [container]
  (if-let [cfg (get-config-by-container container)]
    {:type (registration-value cfg :gui-type)
     :data ((sync-value cfg :sync-get) container)}
    (do
      (log/warn "Cannot sync unknown container type:" (type container))
      nil)))

(defn apply-container-sync-packet
  "Apply sync packet data to client-side container
  
  Args:
  - container: Client-side container instance
  - packet-data: Data from server sync packet"
  [container packet-data]
  (let [{:keys [type data]} packet-data
        cfg (gui-registry/get-gui-by-type type)]
    (if cfg
      ((sync-value cfg :sync-apply) container data)
      (log/warn "Unknown sync packet type:" type))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize GUI system
  
  Args:
  - platform-type: Keyword identifying platform (:forge-1.16.5, etc.)"
  [platform-type]
  (log/info "Initializing Wireless GUI system for platform:" platform-type)
  
  ;; Register GUI handler
  (register-gui-handler platform-type)
  
  ;; Initialize handler
  (get-gui-handler)
  
  (log/info "Wireless GUI system initialized"))
