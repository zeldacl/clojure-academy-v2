(ns cn.li.ac.wireless.api.adapter
  "Anti-corruption layer for gradual migration.
  
  Bridges old wireless.data.* APIs to new wireless.api.* APIs.
  Allows existing blocks to continue working while new code uses
  the clean API. Enables risk-free gradual migration."
  (:require [cn.li.ac.wireless.api.impl :as api-impl]
            [cn.li.ac.wireless.api.protocol :as proto]
            [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.wireless.domain.node :as domain-node]
            [cn.li.ac.wireless.persistence.nbt-codec :as codec]
            [cn.li.ac.wireless.service.network-manager :as manager]
            [cn.li.ac.wireless.persistence.world-loader :as world-loader]
            [cn.li.ac.wireless.data.world :as old-world]
            [cn.li.ac.wireless.data.network :as old-network]
            [cn.li.ac.foundation.vblock :as vb]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Singleton API Instance
;; ============================================================================

(def ^:private global-api-instance (atom nil))

(defn get-or-create-api
  "Get or create the global wireless API instance.
  
  Returns:
    WirelessAPIImpl instance"
  []
  (or @global-api-instance
      (let [api (api-impl/create-wireless-api)]
        (reset! global-api-instance api)
        (log/info "Created global wireless API instance")
        api)))

;; ============================================================================
;; Backward Compatibility Functions
;; ============================================================================

(defn register-old-network!
  "Register an old-style network (from wireless.data.network) into new API.
  
  Args:
    old-net: Old WirelessNet record
    
  Returns:
    New Network domain object"
  [old-net]
  (let [api (get-or-create-api)
        matrix-vblock (:matrix old-net)
        ssid @(:ssid old-net)
        password @(:password old-net)
        buffer @(:buffer old-net)
        max-capacity (if-let [matrix-te (old-network/get-matrix old-net)]
                       (.getMatrixCapacity ^cn.li.acapi.wireless.IWirelessMatrix matrix-te)
                       10000.0)]
    
    ;; Create new network via API
    (if-let [new-net (proto/create-network api ssid password matrix-vblock max-capacity)]
      (do
        ;; Copy over nodes
        (doseq [node-vb @(:nodes old-net)]
          (proto/connect-node api (:id new-net) node-vb :receiver))
        
        ;; Set energy
        (proto/set-network-energy api (:id new-net) buffer)
        
        (log/info (str "Registered old network to new API: " ssid))
        new-net)
      (do
        (log/warn (str "Failed to register network " ssid " to new API"))
        nil))))

(defn find-network-for-node
  "Find which network a node belongs to (new API).
  
  Args:
    node-vblock: VBlock position
    
  Returns:
    Network or nil"
  [node-vblock]
  (let [api (get-or-create-api)
        networks (proto/list-networks api)]
    (some (fn [net]
            (when (domain-net/contains-node? net node-vblock)
              net))
          networks)))

(defn connect-node-to-network!
  "Connect a node to a network (new API).
  
  Args:
    network-id: Network ID
    node-vblock: VBlock to connect
    
  Returns:
    {:success boolean :reason string}"
  [network-id node-vblock]
  (let [api (get-or-create-api)]
    (proto/connect-node api network-id node-vblock :receiver)))

;; ============================================================================
;; World Data Integration
;; ============================================================================

(defn sync-world-to-api!
  "Synchronize world data with new API.
  
  Called when world loads to register all networks.
  
  Args:
    world-data: Old world-data atom
    
  Returns:
    nil"
  [world-data]
  (try
    (let [api (get-or-create-api)
          old-networks @(:networks world-data)]
      (doseq [old-net old-networks]
        (register-old-network! old-net)))
    (catch Exception e
      (log/error (str "Failed to sync world to API: " (.getMessage e))))))

(defn save-api-to-world!
  "Save API networks to world NBT.
  
  Args:
    world-compound: NBT compound to modify
    
  Returns:
    Updated world-compound"
  [world-compound]
  (try
    (let [api (get-or-create-api)]
      (proto/save-to-world api world-compound))
    (catch Exception e
      (log/error (str "Failed to save API to world: " (.getMessage e)))
      world-compound)))

;; ============================================================================
;; Gradual Migration Helpers
;; ============================================================================

(defn use-new-api-for-queries?
  "Feature flag: Use new API for queries (gradual rollout).
  
  Returns:
    boolean"
  []
  (try
    (Boolean/parseBoolean (System/getProperty "ac.wireless.use-new-api" "false"))
    (catch Exception _ false)))

(defn wrap-old-function
  "Wrap old wireless.data functions to use new API if enabled.
  
  Args:
    old-fn: Original function
    new-fn: New API function
    
  Returns:
    Function that routes based on feature flag"
  [old-fn new-fn]
  (fn [& args]
    (if (use-new-api-for-queries?)
      (try
        (apply new-fn args)
        (catch Exception e
          (log/warn (str "New API call failed, falling back: " (.getMessage e)))
          (apply old-fn args)))
      (apply old-fn args))))

;; ============================================================================
;; Testing & Debugging
;; ============================================================================

(defn reset-api!
  "Reset API to clean state (for testing).
  
  Returns:
    nil"
  []
  (reset! global-api-instance nil)
  (log/info "Reset wireless API"))

(defn dump-api-state
  "Dump current API state for debugging.
  
  Returns:
    State map"
  []
  (let [api (get-or-create-api)]
    (if (extends? proto/IWirelessAdmin (type api))
      (proto/admin-dump-state api)
      {:error "API does not support admin operations"})))

(defn validate-api-consistency
  "Validate API consistency (for debugging).
  
  Returns:
    {:valid boolean :errors [string]}"
  []
  (let [api (get-or-create-api)]
    (if (extends? proto/IWirelessAdmin (type api))
      (proto/admin-validate-consistency api)
      {:valid false :errors ["Admin API not supported"]})))
