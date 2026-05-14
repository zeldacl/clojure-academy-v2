(ns cn.li.ac.wireless.service.energy-balance
  "Energy balance and distribution service.
  
  Handles energy transfer between networks and nodes,
  maintaining consistency and efficiency constraints."
  (:require [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.wireless.domain.energy :as domain-energy]
            [cn.li.ac.foundation.validation :as val]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Energy Balance Strategy
;; ============================================================================

(defn create-balance-strategy
  "Create energy balancing strategy.
  
  Args:
    name: keyword - strategy identifier
    balance-fn: (fn [networks] [balanced-networks])
    
  Returns:
    Strategy map"
  [name balance-fn]
  {:name name
   :balance-fn balance-fn})

;; ============================================================================
;; Distribution Algorithms
;; ============================================================================

(defn equal-distribution
  "Distribute energy equally among networks.
  
  Each network gets (total / count) energy.
  
  Args:
    networks: Vector of Network records
    
  Returns:
    Vector of updated networks"
  [networks]
  (if (empty? networks)
    []
    (let [total-energy (reduce + 0.0 (map domain-net/get-energy networks))
          num-networks (count networks)
          target-per-network (/ total-energy num-networks)]
      (mapv (fn [net] (domain-net/set-energy net target-per-network)) networks))))

(defn proportional-distribution
  "Distribute energy proportional to network capacity.
  
  Networks with larger max-capacity get more energy.
  
  Args:
    networks: Vector of Network records
    
  Returns:
    Vector of updated networks"
  [networks]
  (if (empty? networks)
    []
    (let [total-energy (reduce + 0.0 (map domain-net/get-energy networks))
          total-capacity (reduce + 0.0 (map domain-net/get-max-energy networks))]
      (if (> total-capacity 0)
        (mapv (fn [net]
                (let [proportion (/ (domain-net/get-max-energy net) total-capacity)
                      share (* total-energy proportion)]
                  (domain-net/set-energy net share)))
              networks)
        networks))))

(defn load-balanced-distribution
  "Distribute energy based on current node count.
  
  Networks with more nodes get more energy.
  
  Args:
    networks: Vector of Network records
    
  Returns:
    Vector of updated networks"
  [networks]
  (if (empty? networks)
    []
    (let [total-energy (reduce + 0.0 (map domain-net/get-energy networks))
          total-nodes (reduce + 0 (map domain-net/get-node-count networks))]
      (if (> total-nodes 0)
        (mapv (fn [net]
                (let [node-count (domain-net/get-node-count net)
                      proportion (if (> total-nodes 0)
                                  (/ node-count total-nodes)
                                  0)
                      share (* total-energy proportion)]
                  (domain-net/set-energy net share)))
              networks)
        networks))))

(defn priority-distribution
  "Distribute energy prioritizing high-load networks.
  
  First satisfy high-priority networks to capacity, then distribute remaining.
  
  Args:
    networks: Vector of Network records
    priority-fn: (fn [network] number) - higher number = higher priority
    
  Returns:
    Vector of updated networks"
  [networks priority-fn]
  (if (empty? networks)
    []
    (let [total-energy (reduce + 0.0 (map domain-net/get-energy networks))
          scored (mapv (fn [net] {:net net :score (priority-fn net)}) networks)
          sorted (sort-by (fn [x] (- (:score x))) scored)]
      (loop [remaining total-energy
             result []
             to-process sorted]
        (if (empty? to-process)
          result
          (let [{:keys [net]} (first to-process)
                max-cap (domain-net/get-max-energy net)
                alloc (min remaining max-cap)
                updated (domain-net/set-energy net alloc)]
            (recur (- remaining alloc)
                   (conj result updated)
                   (rest to-process))))))))

;; ============================================================================
;; Transfer Logic
;; ============================================================================

(defn transfer-energy-between-networks
  "Transfer energy from source to destination network.
  
  Args:
    source-net: Source Network record
    dest-net: Destination Network record
    amount: Energy to transfer
    efficiency: 0.0-1.0 transfer efficiency
    
  Returns:
    {:source updated-source
     :dest updated-dest
     :transferred amount-transferred
     :lost amount-lost}"
  [source-net dest-net amount efficiency]
  (let [efficiency (-> efficiency (max 0.0) (min 1.0))
        available (domain-net/get-energy source-net)
        extractable (min available amount)
        effective (* extractable efficiency)
        loss (- extractable effective)
        
        max-dest (domain-net/get-max-energy dest-net)
        current-dest (domain-net/get-energy dest-net)
        space (- max-dest current-dest)
        receivable (min effective space)
        
        source-after (domain-net/set-energy source-net
                                             (- (domain-net/get-energy source-net) extractable))
        dest-after (domain-net/set-energy dest-net
                                          (+ current-dest receivable))]
    {:source source-after
     :dest dest-after
     :transferred receivable
     :lost loss}))

(defn cascade-transfer
  "Transfer energy through chain of networks sequentially.
  
  Energy flows from network to network with loss at each step.
  
  Args:
    networks: Vector of Network records in transfer order
    total-amount: Total energy to inject at start
    efficiency-per-hop: Efficiency factor per transfer
    
  Returns:
    Vector of updated networks"
  [networks total-amount efficiency-per-hop]
  (if (empty? networks)
    []
    (loop [remaining total-amount
           updated-nets networks
           index 0]
      (if (or (>= index (count updated-nets))
              (<= remaining 0.0))
        updated-nets
        (let [current-net (nth updated-nets index)
                max-cap (domain-net/get-max-energy current-net)
                current-energy (domain-net/get-energy current-net)
                space (- max-cap current-energy)
                effective-amount (* remaining efficiency-per-hop)
                to-add (min effective-amount space)
                loss (* remaining (- 1.0 efficiency-per-hop))
                new-net (domain-net/set-energy current-net
                                                (+ current-energy to-add))
                updated (assoc updated-nets index new-net)
                next-remaining (- remaining to-add loss)]
          (recur next-remaining updated (inc index)))))))

;; ============================================================================
;; Network Balancing
;; ============================================================================

(defn should-balance?
  "Check if network needs balancing.
  
  Args:
    registry-atom: Atom containing all networks
    balance-threshold: 0.0-1.0 max deviation from average
    
  Returns:
    boolean"
  [registry-atom balance-threshold]
  (let [networks (vals @registry-atom)
        energies (map domain-net/get-energy networks)
        avg-energy (if (empty? energies)
                    0.0
                    (/ (reduce + 0.0 energies) (count energies)))]
    (some (fn [net]
            (let [current (domain-net/get-energy net)
                  deviation (if (> avg-energy 0)
                            (Math/abs (- current avg-energy))
                            0)]
              (> deviation (* avg-energy balance-threshold))))
          networks)))

(defn balance-all-networks!
  "Balance energy across all networks in registry.
  
  Args:
    registry-atom: Atom containing all networks
    strategy: Balance strategy map
    
  Returns:
    {:networks updated-networks
     :total-energy total
     :balanced boolean}"
  [registry-atom strategy]
  (let [networks (vals @registry-atom)
        before-total (reduce + 0.0 (map domain-net/get-energy networks))]
    (try
      (let [balanced ((:balance-fn strategy) networks)
            after-total (reduce + 0.0 (map domain-net/get-energy balanced))
            ;; Update registry with balanced networks
            _ (doseq [net balanced]
                (swap! registry-atom assoc (:id net) net))]
        {:networks balanced
         :total-energy after-total
         :balanced true})
      (catch Exception e
        (log/error (str "Balance failed: " (.getMessage e)))
        {:networks networks
         :total-energy before-total
         :balanced false}))))

;; ============================================================================
;; Tick-based Balancing
;; ============================================================================

(defn should-tick-balance?
  "Check if enough ticks have passed for balance.
  
  Args:
    ticks-since-last: int
    balance-interval: int ticks between balance attempts
    
  Returns:
    boolean"
  [ticks-since-last balance-interval]
  (>= ticks-since-last balance-interval))

(defn tick-balance-networks!
  "Perform tick-based balancing (called periodically).
  
  Args:
    registry-atom: Atom with networks
    current-tick: Current world tick
    last-balance-tick: Last tick balance ran
    balance-interval: Ticks between balances
    strategy: Balance strategy
    
  Returns:
    {:balanced boolean
     :next-balance-tick next-tick}"
  [registry-atom current-tick last-balance-tick balance-interval strategy]
  (let [ticks-since (- current-tick last-balance-tick)]
    (if (should-tick-balance? ticks-since balance-interval)
      (let [result (balance-all-networks! registry-atom strategy)]
        (log/info (str "Balanced " (count (:networks result))
                       " networks, total energy: "
                       (format "%.1f" (:total-energy result))))
        {:balanced (:balanced result)
         :next-balance-tick current-tick})
      {:balanced false
       :next-balance-tick last-balance-tick})))

;; ============================================================================
;; Default Strategies
;; ============================================================================

(def strategy-equal
  "Balance energy equally across all networks"
  (create-balance-strategy :equal equal-distribution))

(def strategy-proportional
  "Balance energy proportional to network capacity"
  (create-balance-strategy :proportional proportional-distribution))

(def strategy-load-balanced
  "Balance energy proportional to node count"
  (create-balance-strategy :load-balanced load-balanced-distribution))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-transfer-result
  "Validate energy transfer didn't violate constraints.
  
  Args:
    source: Source Network
    dest: Destination Network
    result: Transfer result map
    
  Returns:
    {:valid boolean :errors [string]}"
  [source dest result]
  (let [source-energy (domain-net/get-energy (:source result))
        dest-energy (domain-net/get-energy (:dest result))
        dest-max (domain-net/get-max-energy (:dest result))
        errors (cond-> []
                 (< source-energy 0) (conj "Source energy negative")
                 (< dest-energy 0) (conj "Dest energy negative")
                 (> dest-energy dest-max) (conj "Dest energy exceeds capacity"))]
    {:valid (empty? errors) :errors errors}))
