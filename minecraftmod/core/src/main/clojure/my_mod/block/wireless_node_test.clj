(ns my-mod.block.wireless-node-test
  "Unit tests for Wireless Node implementation"
  (:require [my-mod.block.wireless-node :as wnode]
            [my-mod.energy.stub :as energy]
            [my-mod.util.log :as log]))

;; Test node creation
(defn test-node-creation []
  (log/info "Testing node creation...")
  (let [tile (wnode/create-node-tile-entity :basic "world" {:x 0 :y 64 :z 0})]
    (assert (= (:node-type tile) :basic))
    (assert (= (wnode/get-max-energy tile) 15000))
    (assert (= (wnode/get-bandwidth tile) 150))
    (assert (= (wnode/get-range tile) 9))
    (assert (= (wnode/get-capacity tile) 5))
    (assert (= (wnode/get-energy tile) 0.0))
    (log/info "✓ Node creation works")))

;; Test energy operations
(defn test-energy-operations []
  (log/info "Testing energy operations...")
  (let [tile (wnode/create-node-tile-entity :standard "world" {:x 0 :y 64 :z 0})]
    ;; Test set energy
    (wnode/set-energy! tile 1000.0)
    (assert (= (wnode/get-energy tile) 1000.0))
    
    ;; Test energy clamping (max)
    (wnode/set-energy! tile 100000.0)
    (assert (= (wnode/get-energy tile) 50000.0)) ; standard max
    
    ;; Test energy clamping (min)
    (wnode/set-energy! tile -100.0)
    (assert (= (wnode/get-energy tile) 0.0))
    
    (log/info "✓ Energy operations work")))

;; Test energy percentage levels
(defn test-energy-percentage []
  (log/info "Testing energy percentage levels...")
  (let [tile (wnode/create-node-tile-entity :basic "world" {:x 0 :y 64 :z 0})]
    ;; 0%
    (wnode/set-energy! tile 0.0)
    (assert (= (wnode/get-energy-percentage-level tile) 0))
    
    ;; 25%
    (wnode/set-energy! tile 3750.0)
    (assert (= (wnode/get-energy-percentage-level tile) 1))
    
    ;; 50%
    (wnode/set-energy! tile 7500.0)
    (assert (= (wnode/get-energy-percentage-level tile) 2))
    
    ;; 75%
    (wnode/set-energy! tile 11250.0)
    (assert (= (wnode/get-energy-percentage-level tile) 3))
    
    ;; 100%
    (wnode/set-energy! tile 15000.0)
    (assert (= (wnode/get-energy-percentage-level tile) 4))
    
    (log/info "✓ Energy percentage levels work")))

;; Test inventory operations
(defn test-inventory []
  (log/info "Testing inventory operations...")
  (let [tile (wnode/create-node-tile-entity :advanced "world" {:x 0 :y 64 :z 0})
        test-item "test-battery"]
    ;; Test empty inventory
    (assert (nil? (wnode/get-inventory-slot tile 0)))
    (assert (nil? (wnode/get-inventory-slot tile 1)))
    
    ;; Test set inventory
    (wnode/set-inventory-slot! tile 0 test-item)
    (assert (= (wnode/get-inventory-slot tile 0) test-item))
    
    (log/info "✓ Inventory operations work")))

;; Test node types
(defn test-node-types []
  (log/info "Testing node types...")
  ;; Basic
  (let [basic (wnode/create-node-tile-entity :basic "world" {:x 0 :y 64 :z 0})]
    (assert (= (wnode/get-max-energy basic) 15000))
    (assert (= (wnode/get-bandwidth basic) 150)))
  
  ;; Standard
  (let [standard (wnode/create-node-tile-entity :standard "world" {:x 0 :y 64 :z 0})]
    (assert (= (wnode/get-max-energy standard) 50000))
    (assert (= (wnode/get-bandwidth standard) 300)))
  
  ;; Advanced
  (let [advanced (wnode/create-node-tile-entity :advanced "world" {:x 0 :y 64 :z 0})]
    (assert (= (wnode/get-max-energy advanced) 200000))
    (assert (= (wnode/get-bandwidth advanced) 900)))
  
  (log/info "✓ Node types work")))

;; Test charging simulation
(defn test-charging []
  (log/info "Testing charging simulation...")
  (let [tile (wnode/create-node-tile-entity :basic "world" {:x 0 :y 64 :z 0})]
    ;; Test charge in
    (wnode/set-inventory-slot! tile 0 "test-battery")
    (wnode/update-charge-in! tile)
    (assert (> (wnode/get-energy tile) 0.0))
    (assert @(:charging-in tile))
    
    ;; Test charge out
    (wnode/set-energy! tile 1000.0)
    (wnode/set-inventory-slot! tile 1 "test-battery")
    (wnode/update-charge-out! tile)
    (assert (< (wnode/get-energy tile) 1000.0))
    (assert @(:charging-out tile))
    
    (log/info "✓ Charging simulation works")))

;; Test network connection
(defn test-network-connection []
  (log/info "Testing network connection...")
  (let [tile (wnode/create-node-tile-entity :standard "world" {:x 0 :y 64 :z 0})]
    ;; No password - not connected
    (wnode/check-network-connection! tile)
    (assert (false? @(:enabled tile)))
    
    ;; With password - connected (stub logic)
    (let [tile-with-pass (assoc tile :password "test123")]
      (wnode/check-network-connection! tile-with-pass)
      (assert (true? @(:enabled tile-with-pass))))
    
    (log/info "✓ Network connection works")))

;; Test NBT serialization
(defn test-nbt-serialization []
  (log/info "Testing NBT serialization...")
  (let [tile (wnode/create-node-tile-entity :advanced "world" {:x 0 :y 64 :z 0})]
    ;; Set some data
    (wnode/set-energy! tile 12345.0)
    (let [tile2 (-> tile
                    (assoc :node-name "TestNode")
                    (assoc :password "secret")
                    (assoc :placer-name "Player1"))]
      
      ;; Serialize
      (let [nbt (wnode/node-to-nbt tile2)]
        (assert (= (:energy nbt) 12345.0))
        (assert (= (:node-name nbt) "TestNode"))
        (assert (= (:password nbt) "secret"))
        (assert (= (:placer-name nbt) "Player1"))
        
        ;; Deserialize
        (let [tile3 (wnode/create-node-tile-entity :advanced "world" {:x 0 :y 64 :z 0})
              restored (wnode/node-from-nbt tile3 nbt)]
          (assert (= (wnode/get-energy restored) 12345.0))
          (assert (= (:node-name restored) "TestNode"))
          (assert (= (:password restored) "secret"))
          (assert (= (:placer-name restored) "Player1"))))
      
      (log/info "✓ NBT serialization works"))))

;; Test block definitions
(defn test-block-definitions []
  (log/info "Testing block definitions...")
  (assert (some? wnode/wireless-node-basic))
  (assert (some? wnode/wireless-node-standard))
  (assert (some? wnode/wireless-node-advanced))
  (assert (= (:id wnode/wireless-node-basic) "wireless-node-basic"))
  (assert (= (:material wnode/wireless-node-basic) :metal))
  (assert (= (:hardness wnode/wireless-node-basic) 2.5))
  (log/info "✓ Block definitions work"))

;; Test tile entity registry
(defn test-tile-registry []
  (log/info "Testing tile entity registry...")
  (let [pos {:x 100 :y 64 :z 200}
        tile (wnode/create-node-tile-entity :basic "world" pos)]
    ;; Register
    (wnode/register-node-tile! pos tile)
    (assert (= (wnode/get-node-tile pos) tile))
    
    ;; Unregister
    (wnode/unregister-node-tile! pos)
    (assert (nil? (wnode/get-node-tile pos)))
    
    (log/info "✓ Tile entity registry works")))

;; Run all tests
(defn run-all-tests []
  (log/info "=== Running Wireless Node Tests ===")
  (try
    (test-node-creation)
    (test-energy-operations)
    (test-energy-percentage)
    (test-inventory)
    (test-node-types)
    (test-charging)
    (test-network-connection)
    (test-nbt-serialization)
    (test-block-definitions)
    (test-tile-registry)
    (log/info "=== All Tests Passed! ===")
    true
    (catch Exception e
      (log/info "=== Test Failed! ===")
      (.printStackTrace e)
      false)))

;; Auto-run info
(defn init-tests! []
  (log/info "Wireless Node tests available. Run (run-all-tests) to execute."))
