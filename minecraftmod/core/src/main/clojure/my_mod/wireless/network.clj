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
   ssid             ; String - network name
   password         ; String - network password
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
    ssid
    password
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
(defn get-ssid [network] (:ssid network))
(defn get-password [network] (:password network))
(defn get-load [network] (count @(:nodes network)))

(defn get-capacity
  "Get network capacity from matrix"
  [network]
  (if-let [matrix (get-matrix network)]
    (.getCapacity matrix)
    0))

;; ============================================================================
;; Password Management
;; ============================================================================

(defn reset-password!
  "Change network password"
  [network new-password]
  (set! (. network password) new-password)
  (log/info (format "Network '%s' password changed" (:ssid network)))
  true)

;; ============================================================================
;; Node Management
;; ============================================================================

(defn add-node!
  "Add a node to the network
  Returns true if successful, false otherwise"
  [network node-vblock password-attempt]
  ;; Validate password
  (when (not= password-attempt (:password network))
    (log/info (format "Node add failed: incorrect password for '%s'" (:ssid network)))
    (return false))
  
  ;; Check capacity
  (when (>= (get-load network) (get-capacity network))
    (log/info (format "Node add failed: network '%s' at capacity" (:ssid network)))
    (return false))
  
  ;; Check matrix exists
  (let [matrix (get-matrix network)]
    (when-not matrix
      (log/info "Node add failed: matrix not found")
      (return false))
    
    ;; Check range
    (let [range (.getRange matrix)
          dist-sq (vb/dist-sq node-vblock (:matrix network))]
      (when (> dist-sq (* range range))
        (log/info (format "Node add failed: out of range (%.1f > %.1f)"
                          (Math/sqrt dist-sq) range))
        (return false))
      
      ;; Remove from old network if exists
      (let [world-data (:world-data network)
            old-net (my-mod.wireless.world-data/get-network-by-node
                      world-data node-vblock)]
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
      true)))

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
    (let [range (.getRange matrix)
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
            bandwidth (.getBandwidth matrix)]
        
        ;; Calculate total energy and capacity
        (let [energy-data (reduce
                            (fn [acc node-vb]
                              (if (vb/is-chunk-loaded? node-vb world)
                                (if-let [node (vb/vblock-get node-vb world)]
                                  (let [current (.getEnergy node)
                                        max-energy (.getMaxEnergy node)]
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
              (loop [nodes-remaining nodes-shuffled
                     transfer-left bandwidth
                     buffer-val buffer-current]
                (when (and (seq nodes-remaining) (> transfer-left 0))
                  (let [node-vb (first nodes-remaining)]
                    (if (vb/is-chunk-loaded? node-vb world)
                      (if-let [node (vb/vblock-get node-vb world)]
                        (let [current (.getEnergy node)
                              max-energy (.getMaxEnergy node)
                              target (* max-energy average-percent)
                              diff (- current target)]
                          
                          (if (> diff 0)
                            ;; Node has excess energy → pull to buffer
                            (let [to-pull (min diff transfer-left)
                                  buffer-space (- BUFFER_MAX buffer-val)
                                  actual-pull (min to-pull buffer-space)]
                              (.setEnergy node (- current actual-pull))
                              (recur (rest nodes-remaining)
                                     (- transfer-left actual-pull)
                                     (+ buffer-val actual-pull)))
                            
                            ;; Node needs energy → push from buffer
                            (let [to-push (min (- diff) transfer-left buffer-val)]
                              (.setEnergy node (+ current to-push))
                              (recur (rest nodes-remaining)
                                     (- transfer-left to-push)
                                     (- buffer-val to-push)))))
                        
                        ;; Node destroyed, remove
                        (do (remove-node! network node-vb)
                            (recur (rest nodes-remaining) transfer-left buffer-val)))
                      
                      ;; Chunk not loaded, skip
                      (recur (rest nodes-remaining) transfer-left buffer-val))))
                
                ;; Update buffer
                (reset! (:buffer network) buffer-val)))))))))

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
;; NBT Serialization
;; ============================================================================

(defn network-to-nbt
  "Serialize network to NBT"
  [network]
  (let [nbt (net.minecraft.nbt.NBTTagCompound.)]
    ;; Matrix
    (.setTag nbt "matrix" (vb/vblock-to-nbt (:matrix network)))
    
    ;; Info
    (.setString nbt "ssid" (:ssid network))
    (.setString nbt "password" (:password network))
    (.setDouble nbt "buffer" @(:buffer network))
    
    ;; Nodes list
    (let [nodes-list (net.minecraft.nbt.NBTTagList.)
          world (:world (:world-data network))]
      (doseq [node-vb @(:nodes network)]
        ;; Only save valid nodes
        (when (or (not (vb/is-chunk-loaded? node-vb world))
                  (vb/vblock-get node-vb world))
          (.appendTag nodes-list (vb/vblock-to-nbt node-vb))))
      (.setTag nbt "list" nodes-list))
    
    (log/info (format "Saved network '%s' to NBT (%d nodes)"
                      (:ssid network)
                      (count @(:nodes network))))
    nbt))

(defn network-from-nbt
  "Deserialize network from NBT"
  [world-data nbt]
  (let [;; Load basic data
        matrix (vb/vblock-from-nbt (.getCompoundTag nbt "matrix"))
        ssid (.getString nbt "ssid")
        password (.getString nbt "password")
        buffer (.getDouble nbt "buffer")
        
        ;; Create network
        network (create-wireless-net world-data matrix ssid password)]
    
    ;; Restore buffer
    (reset! (:buffer network) buffer)
    
    ;; Load nodes
    (let [nodes-list (.getTagList nbt "list" 10)] ; 10 = compound
      (dotimes [i (.tagCount nodes-list)]
        (let [node-vb (vb/vblock-from-nbt (.getCompoundTagAt nodes-list i))]
          (swap! (:nodes network) conj node-vb))))
    
    (log/info (format "Loaded network '%s' from NBT (%d nodes)"
                      ssid
                      (count @(:nodes network))))
    network))

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
