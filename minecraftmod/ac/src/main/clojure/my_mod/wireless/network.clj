(ns my-mod.wireless.network
  "Wireless Energy Network implementation
  
  Manages SSID-based wireless networks:
  - Node management (add/remove)
  - Energy balancing across nodes
  - Password authentication
  - Range validation"
  (:require [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private UPDATE_INTERVAL 40)     ; Tick between balance updates
(def ^:private BUFFER_MAX 2000.0)      ; Maximum energy buffer

;; ============================================================================
;; WirelessNet Record
;; ============================================================================

(defrecord WirelessNet
  [world-data       ; WiWorldData - parent world data
   matrix           ; VBlock - network matrix (center)
   ssid             ; atom<String> - network name
   password         ; atom<String> - network password
   nodes            ; atom<vector<VBlock>> - connected nodes
   to-remove-nodes  ; atom<vector<VBlock>> - nodes to remove
   buffer           ; atom<double> - energy buffer
   update-counter   ; atom<int> - tick counter
   disposed])       ; atom<boolean> - disposed flag

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-wireless-net
  "Create a new wireless network"
  [world-data matrix-vblock ssid password]
  (->WirelessNet
    world-data
    matrix-vblock
    (atom ssid)        ; ssid
    (atom password)    ; password
    (atom [])          ; nodes
    (atom [])          ; to-remove-nodes
    (atom 0.0)         ; buffer
    (atom 0)           ; update-counter
    (atom false)))     ; disposed

;; ============================================================================
;; Accessors
;; ============================================================================

(defn get-matrix
  "Get the matrix TileEntity"
  [network]
  (vb/vblock-get (:matrix network) (:world (:world-data network))))

(defn is-disposed? [network] @(:disposed network))
(defn get-ssid [network] @(:ssid network))
(defn get-password [network] @(:password network))
(defn get-load [network] (count @(:nodes network)))

(defn get-capacity
  "Get network capacity from matrix"
  [network]
  (if-let [matrix (get-matrix network)]
    (.getMatrixCapacity ^my_mod.api.wireless.IWirelessMatrix matrix)
    0))

(defn- find-existing-network-by-node
  "Lookup existing network for a node via world-data namespace at runtime.
  Uses requiring-resolve to avoid compile-time circular dependency."
  [world-data node-vblock]
  (if-let [lookup-fn (requiring-resolve 'my-mod.wireless.world-data/get-network-by-node)]
    (lookup-fn world-data node-vblock)
    nil))

;; ============================================================================
;; Password Management
;; ============================================================================

(defn reset-password!
  "Change network password"
  [network new-password]
  (reset! (:password network) new-password)
  (log/info (format "Network '%s' password changed" @(:ssid network)))
  true)

(defn reset-ssid!
  "Change network ssid"
  [network new-ssid]
  (reset! (:ssid network) new-ssid)
  (log/info (format "Network ssid changed to '%s'" new-ssid))
  true)

;; ============================================================================
;; Node Management
;; ============================================================================

(declare remove-node!)

(defn add-node!
  "Add a node to the network
  Returns true if successful, false otherwise"
  [network node-vblock password-attempt]
  (cond
    (not= password-attempt (:password network))
    (do
      (log/info (format "Node add failed: incorrect password for '%s'" (:ssid network)))
      false)

    (>= (get-load network) (get-capacity network))
    (do
      (log/info (format "Node add failed: network '%s' at capacity" (:ssid network)))
      false)

    :else
    (let [matrix (get-matrix network)]
      (if-not matrix
        (do
          (log/info "Node add failed: matrix not found")
          false)
        (let [range (.getMatrixRange ^my_mod.api.wireless.IWirelessMatrix matrix)
              dist-sq (vb/dist-sq node-vblock (:matrix network))]
          (if (> dist-sq (* range range))
            (do
              (log/info (format "Node add failed: out of range (%.1f > %.1f)"
                                (Math/sqrt dist-sq) range))
              false)
            (do
              ;; Remove from old network if exists
              (let [world-data (:world-data network)
                  old-net (find-existing-network-by-node world-data node-vblock)]
                (when old-net
                  (remove-node! old-net node-vblock)))

              ;; Add to this network
              (swap! (:nodes network) conj node-vblock)

              ;; Update lookup
              (swap! (:net-lookup (:world-data network))
                     assoc node-vblock network)

              (log/info (format "Added node %s to network '%s'"
                                (vb/vblock-to-string node-vblock)
                                (:ssid network)))
              true)))))))

(defn remove-node!
  "Remove a node from the network"
  [network node-vblock]
  (swap! (:to-remove-nodes network) conj node-vblock)
  (log/info (format "Marked node %s for removal from '%s'"
                    (vb/vblock-to-string node-vblock)
                    (:ssid network))))

(defn- cleanup-removed-nodes!
  "Actually remove nodes marked for removal"
  [network]
  (let [to-remove @(:to-remove-nodes network)]
    (when (seq to-remove)
      ;; Remove from nodes list
      (swap! (:nodes network)
             (fn [nodes]
               (filterv #(not (some (partial vb/vblock-equals? %) to-remove))
                        nodes)))
      
      ;; Remove from lookup
      (doseq [node to-remove]
        (swap! (:net-lookup (:world-data network)) dissoc node))
      
      ;; Clear to-remove list
      (reset! (:to-remove-nodes network) [])
      
      (log/info (format "Removed %d nodes from '%s'"
                        (count to-remove)
                        (:ssid network))))))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate!
  "Validate network integrity
  Returns true if valid, false if should be disposed"
  [network]
  (let [world (:world (:world-data network))
        matrix-vb (:matrix network)]
    (when (vb/is-chunk-loaded? matrix-vb world)
      (when-not (vb/vblock-get matrix-vb world)
        ;; Matrix destroyed
        (reset! (:disposed network) true)
        (log/info (format "Network '%s' disposed: matrix destroyed" (:ssid network)))))
    (not @(:disposed network))))

(defn is-in-range?
  "Check if coordinates are in network range"
  [network x y z]
  (if-let [matrix (get-matrix network)]
    (let [range (.getMatrixRange ^my_mod.api.wireless.IWirelessMatrix matrix)
          dist-sq (vb/dist-sq-pos (:matrix network) x y z)]
      (<= dist-sq (* range range)))
    false))

;; ============================================================================
;; Energy Balancing Algorithm
;; ============================================================================

(defn- balance-energy!
  "Balance energy across all nodes in the network"
  [network]
  (let [world (:world (:world-data network))
        matrix (get-matrix network)]
    (when matrix
      ;; Shuffle nodes for fairness
      (let [nodes-shuffled (shuffle @(:nodes network))
            bandwidth (.getMatrixBandwidth ^my_mod.api.wireless.IWirelessMatrix matrix)]
        
        ;; Calculate total energy and capacity
        (let [energy-data (reduce
                            (fn [acc node-vb]
                              (if (vb/is-chunk-loaded? node-vb world)
                                (if-let [node (vb/vblock-get node-vb world)]
                                  (let [current (.getEnergy ^my_mod.api.wireless.IWirelessNode node)
                                        max-energy (.getMaxEnergy ^my_mod.api.wireless.IWirelessNode node)]
                                    {:sum (+ (:sum acc) current)
                                     :max-sum (+ (:max-sum acc) max-energy)})
                                  acc) ; Node destroyed, skip
                                acc))  ; Chunk not loaded, skip
                            {:sum 0.0 :max-sum 0.0}
                            nodes-shuffled)]
          
          (when (> (:max-sum energy-data) 0)
            (let [average-percent (/ (:sum energy-data) (:max-sum energy-data))
                  buffer-current @(:buffer network)]
              
              ;; Transfer energy
              (let [final-buffer
                    (loop [nodes-remaining nodes-shuffled
                           transfer-left bandwidth
                           buffer-val buffer-current]
                      (if (and (seq nodes-remaining) (> transfer-left 0))
                        (let [node-vb (first nodes-remaining)]
                          (if (vb/is-chunk-loaded? node-vb world)
                            (if-let [node (vb/vblock-get node-vb world)]
                              (let [current (.getEnergy ^my_mod.api.wireless.IWirelessNode node)
                                    max-energy (.getMaxEnergy ^my_mod.api.wireless.IWirelessNode node)
                                    target (* max-energy average-percent)
                                    diff (- current target)]
                                (if (> diff 0)
                                  ;; Node has excess energy → pull to buffer
                                  (let [to-pull (min diff transfer-left)
                                        buffer-space (- BUFFER_MAX buffer-val)
                                        actual-pull (min to-pull buffer-space)]
                                    (.setEnergy ^my_mod.api.wireless.IWirelessNode node (- current actual-pull))
                                    (recur (rest nodes-remaining)
                                           (- transfer-left actual-pull)
                                           (+ buffer-val actual-pull)))

                                  ;; Node needs energy → push from buffer
                                  (let [to-push (min (- diff) transfer-left buffer-val)]
                                    (.setEnergy ^my_mod.api.wireless.IWirelessNode node (+ current to-push))
                                    (recur (rest nodes-remaining)
                                           (- transfer-left to-push)
                                           (- buffer-val to-push)))))

                              ;; Node destroyed, remove
                              (do (remove-node! network node-vb)
                                  (recur (rest nodes-remaining) transfer-left buffer-val)))

                            ;; Chunk not loaded, skip
                            (recur (rest nodes-remaining) transfer-left buffer-val)))

                        buffer-val))]

                ;; Update buffer
                (reset! (:buffer network) final-buffer)))))))))

;; ============================================================================
;; Tick System
;; ============================================================================

(defn tick-wireless-net!
  "Tick the wireless network"
  [network]
  (when-not @(:disposed network)
    ;; Validate
    (when (validate! network)
      ;; Increment counter
      (swap! (:update-counter network) inc)
      
      ;; Balance energy every UPDATE_INTERVAL ticks
      (when (>= @(:update-counter network) UPDATE_INTERVAL)
        (reset! (:update-counter network) 0)
        (balance-energy! network))
      
      ;; Cleanup removed nodes
      (cleanup-removed-nodes! network))))

;; ============================================================================
;; Network Disposal
;; ============================================================================

(defn dispose!
  "Dispose the network and unlink all nodes"
  [network]
  (reset! (:disposed network) true)
  (log/info (format "Network '%s' disposed" (:ssid network))))

;; ============================================================================
;; Network Schema Definition
;; ============================================================================

(def network-schema
  '{:collection-name :network
    :atom-name :networks
    :lookup-atom :net-lookup
    :factory {:fn create-wireless-net
              :args [world-data matrix ssid password]}
    :nbt-fields [{:name :matrix :type :vblock :nbt-key "matrix" :factory-arg true}
                 {:name :ssid :type :string :nbt-key "ssid" :factory-arg true}
                 {:name :password :type :string :nbt-key "password" :factory-arg true}
                 {:name :buffer :type :double :nbt-key "buffer" :atom? true}
                 {:name :nodes :type :vblock-list :nbt-key "list" :atom? true}]
    :create {:args [matrix-vblock ssid password]
             :expr '(my-mod.wireless.network/create-wireless-net world-data matrix-vblock ssid password)
             :return true
             :return-on-unique-fail false
             :unique [{:label "SSID" :value-expr 'ssid :value-fn 'identity}
                      {:label "matrix" :value-expr 'matrix-vblock :value-fn 'my-mod.wireless.virtual-blocks/vblock-to-string}]
             :log-create "Created network: SSID='%s'"
             :log-create-key-expr 'ssid
             :log-create-fail "Cannot create network: %s '%s' already exists"}
    :destroy {:log-destroy "Destroyed network: SSID='%s'"
              :log-destroy-key-expr '(:ssid item)}
    :direct-keys [:matrix :ssid]
    :key-sources [{:values-expr '@(:nodes item)}]
    :log-key-fn 'identity
    :validator {:vblock-key :matrix
                :log-label "networks"}
    :tick {:fn 'my-mod.wireless.network/tick-wireless-net!}
    :nbt {:tag "networks"
          :atom :networks
          :to-nbt 'my-mod.wireless.network/network-to-nbt
          :from-nbt 'my-mod.wireless.network/network-from-nbt
          :skip? '(fn [net] @(:disposed net))
          :rebuild {:lookup-atom :net-lookup
                    :direct-keys [:matrix :ssid]
                    :collection-keys [:nodes]}}})

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(my-mod.wireless.world-schema/defnbt-handlers-from-schema network-schema)

;; ============================================================================
;; Debug
;; ============================================================================

(defn print-network-info
  "Print network information"
  [network]
  (log/info (format "=== Network: %s ===" (:ssid network)))
  (log/info (format "  Load: %d/%d" (get-load network) (get-capacity network)))
  (log/info (format "  Buffer: %.1f/%.1f" @(:buffer network) BUFFER_MAX))
  (log/info (format "  Nodes: %d" (count @(:nodes network))))
  (log/info (format "  Disposed: %s" @(:disposed network))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-wireless-network! []
  (log/info "Wireless network system initialized"))
