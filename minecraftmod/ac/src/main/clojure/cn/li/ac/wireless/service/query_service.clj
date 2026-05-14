(ns cn.li.ac.wireless.service.query-service
  "Unified query service for wireless networks.
  
  Consolidates all query operations that were previously scattered
  across network.clj, network-lookup.clj, and spatial-lookup.clj.
  
  Single source of truth for all queries."
  (:require [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.wireless.domain.node :as domain-node]
            [cn.li.ac.foundation.position :as pos]
            [cn.li.ac.foundation.vblock :as vb]))

;; ============================================================================
;; Query Engine (Spatial Index)
;; ============================================================================

(defn create-query-engine
  "Create query engine with spatial indexing.
  
  Returns:
    atom containing {:networks {network-id network}
                     :spatial-index {chunk-key #{network-ids}}}"
  []
  (atom {:networks {}
         :spatial-index {}}))

;; ============================================================================
;; Network Queries
;; ============================================================================

(defn find-network-by-id
  "Find network by ID.
  
  Args:
    engine: Query engine atom
    network-id: keyword
    
  Returns:
    Network or nil"
  [engine network-id]
  (get-in @engine [:networks network-id]))

(defn find-network-by-ssid
  "Find network by SSID (returns first match).
  
  Args:
    engine: Query engine atom
    ssid: Network name
    
  Returns:
    Network or nil"
  [engine ssid]
  (->> @engine :networks vals (some #(when (= ssid (:ssid %)) %))))

(defn find-networks-by-ssid
  "Find all networks with given SSID.
  
  Args:
    engine: Query engine atom
    ssid: Network name
    
  Returns:
    Vector of networks"
  [engine ssid]
  (->> @engine :networks vals (filterv #(= ssid (:ssid %))) vec))

(defn find-networks-by-matrix-position
  "Find network by matrix position.
  
  Args:
    engine: Query engine atom
    matrix-vblock: VBlock to match
    
  Returns:
    Network or nil"
  [engine matrix-vblock]
  (->> @engine :networks vals
       (some #(when (= (:x (:matrix-vblock %)) (:x matrix-vblock)
                       (:y (:matrix-vblock %)) (:y matrix-vblock)
                       (:z (:matrix-vblock %)) (:z matrix-vblock))
               %))))

;; ============================================================================
;; Node Queries
;; ============================================================================

(defn find-node-in-network
  "Find node by position within a network.
  
  Args:
    network: Network record
    node-vblock: VBlock to find
    
  Returns:
    true if found"
  [network node-vblock]
  (domain-net/contains-node? network node-vblock))

(defn find-all-nodes-in-network
  "Get all nodes in a network.
  
  Args:
    network: Network record
    
  Returns:
    Vector of VBlocks"
  [network]
  (:nodes network))

;; ============================================================================
;; Spatial Range Queries
;; ============================================================================

(defn find-networks-in-range
  "Find all networks within range of position.
  
  Uses spatial indexing for efficiency.
  
  Args:
    engine: Query engine atom
    x, y, z: Center position
    range: Search radius
    
  Returns:
    Vector of networks within range"
  [engine x y z range]
  (let [chunk-keys (pos/nearby-chunk-keys x y z range)
        range-sq (* range range)]
    (->> @engine :networks vals
         (filterv (fn [net]
                    (let [matrix-vb (:matrix-vblock net)
                          dx (- (:x matrix-vb) x)
                          dy (- (:y matrix-vb) y)
                          dz (- (:z matrix-vb) z)
                          dist-sq (+ (* dx dx) (* dy dy) (* dz dz))]
                      (<= dist-sq range-sq))))
         vec)))

(defn find-networks-in-chunk
  "Find networks that have blocks in a chunk.
  
  Args:
    engine: Query engine atom
    cx, cy, cz: Chunk coordinates
    
  Returns:
    Vector of network IDs"
  [engine cx cy cz]
  (get-in @engine [:spatial-index [cx cy cz]] []))

(defn find-networks-in-chunks
  "Find networks spanning multiple chunks.
  
  Args:
    engine: Query engine atom
    chunk-keys: Vector of [cx cy cz]
    
  Returns:
    Vector of network IDs"
  [engine chunk-keys]
  (let [id-set (reduce (fn [acc chunk-key]
                         (into acc (find-networks-in-chunk engine
                                                           (nth chunk-key 0)
                                                           (nth chunk-key 1)
                                                           (nth chunk-key 2))))
                       #{}
                       chunk-keys)]
    (vec id-set)))

;; ============================================================================
;; Aggregate Queries
;; ============================================================================

(defn count-all-networks
  "Get total number of networks.
  
  Args:
    engine: Query engine atom
    
  Returns:
    int"
  [engine]
  (count (get-in @engine [:networks] {})))

(defn count-all-nodes
  "Get total number of nodes across all networks.
  
  Args:
    engine: Query engine atom
    
  Returns:
    int"
  [engine]
  (reduce + 0
          (map domain-net/get-node-count
               (vals (get-in @engine [:networks] {})))))

(defn get-total-energy
  "Get total energy stored in all networks.
  
  Args:
    engine: Query engine atom
    
  Returns:
    double"
  [engine]
  (reduce + 0.0
          (map domain-net/get-energy
               (vals (get-in @engine [:networks] {})))))

(defn get-max-energy
  "Get total max energy capacity of all networks.
  
  Args:
    engine: Query engine atom
    
  Returns:
    double"
  [engine]
  (reduce + 0.0
          (map domain-net/get-max-energy
               (vals (get-in @engine [:networks] {})))))

;; ============================================================================
;; Network Statistics
;; ============================================================================

(defn get-network-stats
  "Get detailed statistics for a network.
  
  Args:
    network: Network record
    
  Returns:
    {:id keyword
     :ssid string
     :node-count int
     :energy-current double
     :energy-max double
     :energy-percent double
     :is-active boolean}"
  [network]
  {:id (:id network)
   :ssid (:ssid network)
   :node-count (domain-net/get-node-count network)
   :energy-current (domain-net/get-energy network)
   :energy-max (domain-net/get-max-energy network)
   :energy-percent (domain-net/get-energy-percent network)
   :is-active (domain-net/is-active? network)})

(defn get-all-network-stats
  "Get statistics for all networks.
  
  Args:
    engine: Query engine atom
    
  Returns:
    Vector of stats maps"
  [engine]
  (->> @engine :networks vals (mapv get-network-stats) vec))

;; ============================================================================
;; Index Management
;; ============================================================================

(defn register-network!
  "Register network in query engine.
  
  Args:
    engine: Query engine atom
    network: Network record
    
  Returns:
    Network"
  [engine network]
  (swap! engine
         (fn [e]
           (assoc-in e [:networks (:id network)] network)))
  network)

(defn unregister-network!
  "Unregister network from query engine.
  
  Args:
    engine: Query engine atom
    network-id: Network ID
    
  Returns:
    Network"
  [engine network-id]
  (let [network (get-in @engine [:networks network-id])]
    (swap! engine
           (fn [e]
             (update-in e [:networks] dissoc network-id)))
    network))

(defn update-spatial-index!
  "Update spatial index for network.
  
  Args:
    engine: Query engine atom
    network-id: Network ID
    old-matrix-vblock: Previous matrix position (or nil)
    new-matrix-vblock: New matrix position
    
  Returns:
    nil"
  [engine network-id old-matrix-vblock new-matrix-vblock]
  (let [old-chunk (when old-matrix-vblock (vb/vblock->chunk-key old-matrix-vblock))
        new-chunk (vb/vblock->chunk-key new-matrix-vblock)]
    (swap! engine
           (fn [e]
             (let [spatial (:spatial-index e)
                   ;; Remove from old chunk
                   spatial (if old-chunk
                             (update spatial old-chunk
                                    (fn [ids] (filterv #(not= % network-id) ids)))
                             spatial)
                   ;; Add to new chunk
                   spatial (update spatial new-chunk
                                  (fn [ids] (conj (or ids []) network-id)))]
               (assoc e :spatial-index spatial))))))

;; ============================================================================
;; Cache Invalidation
;; ============================================================================

(defn invalidate-cache!
  "Fully rebuild query cache (expensive operation).
  
  Use after bulk operations or if cache becomes inconsistent.
  
  Args:
    engine: Query engine atom
    
  Returns:
    nil"
  [engine]
  (let [networks (get-in @engine [:networks] {})]
    (swap! engine
           (fn [e]
             (let [spatial {}
                   spatial (reduce (fn [acc [net-id network]]
                                     (let [chunk (vb/vblock->chunk-key (:matrix-vblock network))]
                                       (update acc chunk
                                              (fn [ids] (conj (or ids []) net-id)))))
                                   spatial
                                   networks)]
               (assoc e :spatial-index spatial))))))

;; ============================================================================
;; Utility Queries
;; ============================================================================

(defn get-all-networks
  "Get all networks.
  
  Args:
    engine: Query engine atom
    
  Returns:
    Vector of networks"
  [engine]
  (vec (vals (get-in @engine [:networks] {}))))

(defn get-networks-sorted-by-energy
  "Get networks sorted by current energy (descending).
  
  Args:
    engine: Query engine atom
    
  Returns:
    Vector of networks"
  [engine]
  (->> @engine :networks vals
       (sort-by #(- (domain-net/get-energy %)))
       vec))

(defn get-networks-sorted-by-nodes
  "Get networks sorted by node count (descending).
  
  Args:
    engine: Query engine atom
    
  Returns:
    Vector of networks"
  [engine]
  (->> @engine :networks vals
       (sort-by #(- (domain-net/get-node-count %)))
       vec))

(defn filter-active-networks
  "Get only networks with nodes.
  
  Args:
    engine: Query engine atom
    
  Returns:
    Vector of active networks"
  [engine]
  (->> @engine :networks vals
       (filterv domain-net/is-active?)
       vec))
