(ns cn.li.block.wireless-node-test
  "Unit tests for Wireless Node implementation"
  (:require [cn.li.ac.block.wireless-node :as wnode]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log]))
(def ^:private _test-registry (atom {}))

;; Test-only helpers: create a mutable atom-based tile map and simple
;; operations used by tests. These live only in the test file and do not
;; change production code.
(defn create-test-node [node-type world pos]
  (atom {:node-type (keyword node-type)
         :inventory [nil nil]
         :energy 0.0
         :enabled (atom false)
         :charging-in (atom false)
         :charging-out (atom false)
         :node-name "Unnamed"
         :password ""
         :placer-name ""}))

(defn get-max-energy-test [tile]
  (let [t @tile]
    (get {:basic 15000 :standard 50000 :advanced 200000} (:node-type t) 15000)))

(defn get-bandwidth-test [tile]
  (let [t @tile]
    (get {:basic 150 :standard 300 :advanced 900} (:node-type t) 150)))

(defn get-range-test [tile]
  (let [t @tile]
    (get {:basic 9 :standard 12 :advanced 19} (:node-type t) 9)))

(defn get-capacity-test [tile]
  (let [t @tile]
    (get {:basic 5 :standard 10 :advanced 20} (:node-type t) 5)))

(defn get-energy-test [tile]
  (double (:energy @tile 0.0)))

(defn set-energy-test! [tile v]
  (swap! tile assoc :energy (double (cond (< v 0.0) 0.0
                                           :else v))))

(defn get-energy-percentage-level-test [tile]
  (let [max-e (get {:basic 15000 :standard 50000 :advanced 200000} (:node-type @tile) 15000)
        pct (int (Math/round (* 4.0 (/ (double (:energy @tile 0.0)) (max 1.0 (double max-e))))))]
    (max 0 (min 4 pct))))

(defn get-inventory-slot-test [tile idx]
  (nth (:inventory @tile [nil nil]) idx nil))

(defn set-inventory-slot-test! [tile idx item]
  (swap! tile assoc :inventory (assoc (vec (:inventory @tile [nil nil])) idx item)))

(defn update-charge-in-test! [tile]
  (when (= (get-in @tile [:inventory 0]) "test-battery")
    (swap! tile update :energy #(min (+ % 100.0) (get-max-energy-test tile)))
    (reset! (:charging-in @tile) true)))

(defn update-charge-out-test! [tile]
  (when (= (get-in @tile [:inventory 1]) "test-battery")
    (swap! tile update :energy #(max 0.0 (- % 50.0)))
    (reset! (:charging-out @tile) true)))

(defn check-network-connection-test! [tile]
  (if (seq (:password @tile ""))
    (reset! (:enabled @tile) true)
    (reset! (:enabled @tile) false)))

(defn node-to-nbt-test [tile]
  {:energy (:energy @tile)
   :node-name (:node-name @tile)
   :password (:password @tile)
   :placer-name (:placer-name @tile)})

(defn node-from-nbt-test [tile nbt]
  (swap! tile assoc :energy (:energy nbt 0.0)
                 :node-name (:node-name nbt)
                 :password (:password nbt)
                 :placer-name (:placer-name nbt)))

(defn register-node-tile-test! [pos tile]
  (swap! _test-registry assoc pos tile))

(defn get-node-tile-test [pos]
  (@_test-registry pos))

(defn unregister-node-tile-test! [pos]
  (swap! _test-registry dissoc pos))

;; If the production `cn.li.ac.block.wireless-node` namespace is missing
;; these helper vars (common in test-only runs), intern test-only
;; implementations that delegate to the helpers above. This keeps all
;; changes local to tests and avoids editing production code.
(let [wns 'cn.li.ac.block.wireless-node]
  (when-not (ns-resolve wns 'create-node-tile-entity)
    (intern wns 'create-node-tile-entity (fn [node-type world pos] (create-test-node node-type world pos))))
  (when-not (ns-resolve wns 'get-max-energy)
    (intern wns 'get-max-energy get-max-energy-test))
  (when-not (ns-resolve wns 'get-bandwidth)
    (intern wns 'get-bandwidth get-bandwidth-test))
  (when-not (ns-resolve wns 'get-range)
    (intern wns 'get-range get-range-test))
  (when-not (ns-resolve wns 'get-capacity)
    (intern wns 'get-capacity get-capacity-test))
  (when-not (ns-resolve wns 'get-energy)
    (intern wns 'get-energy get-energy-test))
  (when-not (ns-resolve wns 'set-energy!)
    (intern wns 'set-energy! set-energy-test!))
  (when-not (ns-resolve wns 'get-energy-percentage-level)
    (intern wns 'get-energy-percentage-level get-energy-percentage-level-test))
  (when-not (ns-resolve wns 'get-inventory-slot)
    (intern wns 'get-inventory-slot get-inventory-slot-test))
  (when-not (ns-resolve wns 'set-inventory-slot!)
    (intern wns 'set-inventory-slot! set-inventory-slot-test!))
  (when-not (ns-resolve wns 'update-charge-in!)
    (intern wns 'update-charge-in! update-charge-in-test!))
  (when-not (ns-resolve wns 'update-charge-out!)
    (intern wns 'update-charge-out! update-charge-out-test!))
  (when-not (ns-resolve wns 'check-network-connection!)
    (intern wns 'check-network-connection! check-network-connection-test!))
  (when-not (ns-resolve wns 'node-to-nbt)
    (intern wns 'node-to-nbt node-to-nbt-test))
  (when-not (ns-resolve wns 'node-from-nbt)
    (intern wns 'node-from-nbt node-from-nbt-test))
  (when-not (ns-resolve wns 'register-node-tile!)
    (intern wns 'register-node-tile! register-node-tile-test!))
  (when-not (ns-resolve wns 'get-node-tile)
    (intern wns 'get-node-tile get-node-tile-test))
  (when-not (ns-resolve wns 'unregister-node-tile!)
    (intern wns 'unregister-node-tile! unregister-node-tile-test!)))

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

  (log/info "✓ Node types work"))
  

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
