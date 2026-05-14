(ns cn.li.ac.wireless.api.impl
  "Default implementation of wireless API.
  
  Integrates domain, service, and persistence layers
  to provide complete wireless system functionality."
  (:require [cn.li.ac.wireless.api.protocol :as proto]
            [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.wireless.domain.node :as domain-node]
            [cn.li.ac.wireless.service.network-manager :as manager]
            [cn.li.ac.wireless.service.query-service :as query]
            [cn.li.ac.wireless.service.energy-balance :as balance]
            [cn.li.ac.wireless.persistence.world-loader :as world-loader]
            [cn.li.ac.foundation.concurrency :as conc]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; API Implementation
;; ============================================================================

(defrecord WirelessAPIImpl
  [registry-atom            ; Atom with all networks
   query-engine-atom        ; Atom with spatial index
   balance-strategy         ; Balance strategy to use
   balance-interval-ticks   ; Ticks between balancing
   last-balance-tick        ; Tick counter
   shutdown?]               ; shutdown flag atom
  
  proto/IWirelessAPI
  
  ;; ===================== Network Management =====================
  
  (create-network [this ssid password matrix-vblock max-energy]
    (when-not @shutdown?
      (manager/create-network! registry-atom ssid password matrix-vblock max-energy)))
  
  (get-network [this network-id]
    (when-not @shutdown?
      (manager/get-network registry-atom network-id)))
  
  (list-networks [this]
    (when-not @shutdown?
      (manager/get-all-networks registry-atom)))
  
  (find-networks-by-ssid [this ssid]
    (when-not @shutdown?
      (manager/get-networks-by-ssid registry-atom ssid)))
  
  (dispose-network [this network-id]
    (when-not @shutdown?
      (manager/dispose-network! registry-atom network-id)))
  
  ;; ===================== Node Management =====================
  
  (connect-node [this network-id node-vblock node-type]
    (when-not @shutdown?
      (manager/add-node-to-network! registry-atom network-id node-vblock)))
  
  (disconnect-node [this network-id node-vblock]
    (when-not @shutdown?
      (manager/remove-node-from-network! registry-atom network-id node-vblock)))
  
  (get-connected-nodes [this network-id]
    (when-not @shutdown?
      (if-let [net (manager/get-network registry-atom network-id)]
        (:nodes net)
        nil)))
  
  ;; ===================== Energy Management =====================
  
  (get-network-energy [this network-id]
    (when-not @shutdown?
      (if-let [net (manager/get-network registry-atom network-id)]
        (domain-net/get-energy net)
        nil)))
  
  (get-network-max-energy [this network-id]
    (when-not @shutdown?
      (if-let [net (manager/get-network registry-atom network-id)]
        (domain-net/get-max-energy net)
        nil)))
  
  (set-network-energy [this network-id amount]
    (when-not @shutdown?
      (manager/set-network-energy! registry-atom network-id amount)))
  
  (transfer-energy [this from-id to-id amount efficiency]
    (when-not @shutdown?
      (let [result (manager/transfer-network-energy! registry-atom from-id to-id amount efficiency)]
        (if result
          {:success true :transferred (:transferred result)}
          {:success false :transferred 0.0}))))
  
  ;; ===================== Queries =====================
  
  (find-networks-near [this x y z range]
    (when-not @shutdown?
      (query/find-networks-in-range query-engine-atom x y z range)))
  
  (query-network-stats [this network-id]
    (when-not @shutdown?
      (if-let [net (manager/get-network registry-atom network-id)]
        (query/get-network-stats net)
        nil)))
  
  ;; ===================== Persistence =====================
  
  (save-to-world [this world-compound]
    (when-not @shutdown?
      (world-loader/sync-networks-to-world! world-compound registry-atom)))
  
  (load-from-world [this world-compound]
    (when-not @shutdown?
      (let [{:keys [networks]} (world-loader/load-networks-from-world world-compound)]
        (doseq [net networks]
          (query/register-network! query-engine-atom net)
          (swap! registry-atom assoc (:id net) net)))))
  
  ;; ===================== Lifecycle =====================
  
  (tick! [this ticks-elapsed]
    (when-not @shutdown?
      ;; Periodic balancing
      (let [should-balance? (>= ticks-elapsed balance-interval-ticks)]
        (when should-balance?
          (balance/balance-all-networks! registry-atom balance-strategy)
          (reset! last-balance-tick 0)))
      (swap! last-balance-tick + ticks-elapsed)))
  
  (shutdown [this]
    (reset! shutdown? true)
    (world-loader/sync-networks-to-world! (nbt/create-nbt-compound) registry-atom)
    (log/info "Wireless API shut down")))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-wireless-api
  "Create wireless API instance.
  
  Args:
    balance-strategy: Balance strategy (optional, defaults to :equal)
    balance-interval-ticks: Ticks between balancing (optional, default 1200)
    
  Returns:
    WirelessAPIImpl instance"
  [& [{:keys [balance-strategy balance-interval-ticks]
       :or {balance-strategy balance/strategy-equal
            balance-interval-ticks 1200}}]]
  (->WirelessAPIImpl
    (manager/create-network-registry)
    (query/create-query-engine)
    balance-strategy
    balance-interval-ticks
    (atom 0)
    (atom false)))

;; ============================================================================
;; Query Implementation
;; ============================================================================

(defrecord WirelessQueryImpl
  [registry-atom
   query-engine-atom]
  
  proto/IWirelessQuery
  
  (query-network [this network-id]
    (manager/get-network registry-atom network-id))
  
  (query-networks-all [this]
    (manager/get-all-networks registry-atom))
  
  (query-networks-by-ssid [this ssid]
    (manager/get-networks-by-ssid registry-atom ssid))
  
  (query-networks-by-position [this x y z range]
    (query/find-networks-in-range query-engine-atom x y z range))
  
  (query-network-nodes [this network-id]
    (if-let [net (manager/get-network registry-atom network-id)]
      (:nodes net)
      nil))
  
  (query-network-energy [this network-id]
    (if-let [net (manager/get-network registry-atom network-id)]
      {:current (domain-net/get-energy net)
       :max (domain-net/get-max-energy net)
       :percent (domain-net/get-energy-percent net)}
      nil))
  
  (query-statistics [this]
    {:network-count (query/count-all-networks query-engine-atom)
     :total-nodes (query/count-all-nodes query-engine-atom)
     :total-energy (query/get-total-energy query-engine-atom)}))

;; ============================================================================
;; Admin Implementation
;; ============================================================================

(defrecord WirelessAdminImpl
  [registry-atom
   query-engine-atom]
  
  proto/IWirelessAdmin
  
  (admin-reset! [this]
    (reset! registry-atom {})
    (reset! query-engine-atom {:networks {} :spatial-index {}})
    (log/info "Wireless system reset"))
  
  (admin-dump-state [this]
    {:registry @registry-atom
     :query-engine @query-engine-atom})
  
  (admin-validate-consistency [this]
    (let [networks (vals @registry-atom)
          errors (cond-> []
                   (not (every? domain-net/valid-network? networks))
                   (conj "Invalid networks detected"))]
      {:valid (empty? errors) :errors errors}))
  
  (admin-repair [this]
    (let [networks (vals @registry-atom)
          valid (filterv domain-net/valid-network? networks)
          invalid-count (- (count networks) (count valid))]
      (reset! registry-atom {})
      (doseq [net valid]
        (swap! registry-atom assoc (:id net) net))
      (log/warn (str "Repaired: removed " invalid-count " invalid networks"))
      {:repaired invalid-count})))

;; ============================================================================
;; Unified Factory
;; ============================================================================

(defn create-wireless-system
  "Create complete wireless system with all API implementations.
  
  Returns:
    {:api IWirelessAPI
     :query IWirelessQuery
     :admin IWirelessAdmin}"
  [& [options]]
  (let [api (create-wireless-api options)
        query (->WirelessQueryImpl (:registry-atom api) (:query-engine-atom api))
        admin (->WirelessAdminImpl (:registry-atom api) (:query-engine-atom api))]
    {:api api
     :query query
     :admin admin
     :registry-atom (:registry-atom api)
     :query-engine-atom (:query-engine-atom api)}))