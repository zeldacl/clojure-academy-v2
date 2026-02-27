(ns my-mod.block.dsl-test
  "Unit tests for Block DSL"
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.util.log :as log]))

;; Test basic block definition
(defn test-basic-block []
  (log/info "Testing basic block definition...")
  (let [test-spec (bdsl/create-block-spec "test-block"
                    {:material :stone
                     :hardness 2.0
                     :resistance 5.0})]
    (assert (= (:id test-spec) "test-block"))
    (assert (= (:material test-spec) :stone))
    (assert (= (:hardness test-spec) 2.0))
    (assert (= (:resistance test-spec) 5.0))
    (log/info "✓ Basic block definition works")))

;; Test block registry
(defn test-block-registry []
  (log/info "Testing block registry...")
  (bdsl/defblock registry-test-block
    :material :wood
    :hardness 1.0)
  (assert (contains? @bdsl/block-registry "registry-test-block"))
  (assert (= (:id (bdsl/get-block "registry-test-block")) "registry-test-block"))
  (log/info "✓ Block registry works"))

;; Test default values
(defn test-defaults []
  (log/info "Testing default values...")
  (let [spec (bdsl/create-block-spec "default-test" {})]
    (assert (= (:material spec) :stone))
    (assert (= (:hardness spec) bdsl/default-hardness))
    (assert (= (:resistance spec) bdsl/default-resistance))
    (assert (= (:light-level spec) 0))
    (assert (= (:requires-tool spec) false))
    (log/info "✓ Default values work")))

;; Test presets
(defn test-presets []
  (log/info "Testing presets...")
  (let [ore-preset (bdsl/ore-preset 2)
        wood-preset (bdsl/wood-preset)
        metal-preset (bdsl/metal-preset 3)]
    (assert (= (:material ore-preset) :stone))
    (assert (= (:harvest-level ore-preset) 2))
    (assert (= (:material wood-preset) :wood))
    (assert (= (:harvest-tool wood-preset) :axe))
    (assert (= (:material metal-preset) :metal))
    (assert (= (:harvest-level metal-preset) 3))
    (log/info "✓ Presets work")))

;; Test preset merging
(defn test-preset-merge []
  (log/info "Testing preset merging...")
  (let [merged (bdsl/merge-presets
                 (bdsl/ore-preset 2)
                 {:light-level 10
                  :on-right-click (fn [_] (println "clicked"))})]
    (assert (= (:material merged) :stone))
    (assert (= (:harvest-level merged) 2))
    (assert (= (:light-level merged) 10))
    (assert (some? (:on-right-click merged)))
    (log/info "✓ Preset merging works")))

;; Test validation
(defn test-validation []
  (log/info "Testing validation...")
  (try
    (bdsl/create-block-spec nil {:material :stone})
    (assert false "Should have thrown exception for nil id")
    (catch Exception e
      (assert (.contains (.getMessage e) "must have an :id"))))
  
  (try
    (bdsl/create-block-spec "test" {:material :invalid})
    (bdsl/validate-block-spec (bdsl/create-block-spec "test" {:material :invalid}))
    (assert false "Should have thrown exception for invalid material")
    (catch Exception e
      (assert (.contains (.getMessage e) "Invalid material"))))
  
  (log/info "✓ Validation works"))

;; Test interaction handlers
(defn test-handlers []
  (log/info "Testing interaction handlers...")
  (let [clicked (atom false)
        broken (atom false)
        spec (bdsl/create-block-spec "handler-test"
               {:on-right-click (fn [_] (reset! clicked true))
                :on-break (fn [_] (reset! broken true))})]
    (bdsl/handle-right-click spec {})
    (assert @clicked "Right-click handler should have been called")
    (bdsl/handle-break spec {})
    (assert @broken "Break handler should have been called")
    (log/info "✓ Interaction handlers work")))

;; Test block properties extraction
(defn test-properties []
  (log/info "Testing block properties extraction...")
  (let [spec (bdsl/create-block-spec "props-test"
               {:material :metal
                :hardness 5.0
                :resistance 10.0
                :light-level 15
                :requires-tool true})
        props (bdsl/get-block-properties spec)]
    (assert (= (:material props) :metal))
    (assert (= (:hardness props) 5.0))
    (assert (= (:resistance props) 10.0))
    (assert (= (:light-level props) 15))
    (assert (= (:requires-tool props) true))
    (log/info "✓ Properties extraction works")))

;; Test demo blocks - REMOVED (demo.clj deleted)
;; Demo blocks were example implementations, core DSL functionality
;; is tested by other test functions in this file

;; Test materials enum
(defn test-materials []
  (log/info "Testing materials enum...")
  (assert (contains? bdsl/materials :stone))
  (assert (contains? bdsl/materials :wood))
  (assert (contains? bdsl/materials :metal))
  (assert (contains? bdsl/materials :glass))
  (log/info "✓ Materials enum works"))

;; Test tool types enum
(defn test-tool-types []
  (log/info "Testing tool types enum...")
  (assert (contains? bdsl/tool-types :pickaxe))
  (assert (contains? bdsl/tool-types :axe))
  (assert (contains? bdsl/tool-types :shovel))
  (log/info "✓ Tool types enum works"))

;; Test multi-block support
(defn test-multi-block []
  (log/info "Testing multi-block support...")
  (let [spec (bdsl/create-block-spec "test-multiblock"
               {:multi-block? true
                :multi-block-size {:width 2 :height 3 :depth 2}
                :material :metal})]
    (assert (= (:multi-block? spec) true))
    (assert (= (:multi-block-size spec) {:width 2 :height 3 :depth 2}))
    (assert (= (:multi-block-origin spec) {:x 0 :y 0 :z 0}))
    (log/info "✓ Multi-block definition works")))

;; Test multi-block position calculation
(defn test-multi-block-positions []
  (log/info "Testing multi-block position calculation...")
  (let [size {:width 2 :height 2 :depth 2}
        origin {:x 0 :y 0 :z 0}
        positions (bdsl/calculate-multi-block-positions size origin)]
    (assert (= (count positions) 8)) ; 2*2*2 = 8 blocks
    (assert (some :is-origin? positions))
    (assert (every? #(contains? % :relative-x) positions))
    (assert (every? #(contains? % :relative-y) positions))
    (assert (every? #(contains? % :relative-z) positions))
    (log/info "✓ Multi-block position calculation works")))

;; Test multi-block master position
(defn test-multi-block-master-pos []
  (log/info "Testing multi-block master position...")
  (let [part-pos {:x 5 :y 10 :z 3}
        relative-pos {:relative-x 1 :relative-y 2 :relative-z 1}
        master-pos (bdsl/get-multi-block-master-pos part-pos relative-pos)]
    (assert (= master-pos {:x 4 :y 8 :z 2}))
    (log/info "✓ Multi-block master position calculation works")))

;; Test multi-block validation
(defn test-multi-block-validation []
  (log/info "Testing multi-block validation...")
  ;; Should fail without size
  (try
    (let [spec (bdsl/create-block-spec "bad-multiblock"
                 {:multi-block? true})]
      (bdsl/validate-block-spec spec)
      (assert false "Should have thrown exception for missing size"))
    (catch Exception e
      (assert (.contains (.getMessage e) "must have :multi-block-size"))))
  
  ;; Should fail with invalid size
  (try
    (let [spec (bdsl/create-block-spec "bad-multiblock2"
                 {:multi-block? true
                  :multi-block-size {:width 0 :height 2 :depth 2}})]
      (bdsl/validate-block-spec spec)
      (assert false "Should have thrown exception for invalid size"))
    (catch Exception e
      (assert (.contains (.getMessage e) "positive"))))
  
  (log/info "✓ Multi-block validation works"))

;; Test multi-block preset
(defn test-multi-block-preset []
  (log/info "Testing multi-block preset...")
  (let [preset (bdsl/multi-block-preset {:width 3 :height 4 :depth 3})]
    (assert (= (:multi-block? preset) true))
    (assert (= (:multi-block-size preset) {:width 3 :height 4 :depth 3}))
    (assert (= (:material preset) :metal))
    (assert (some? (:hardness preset)))
    (log/info "✓ Multi-block preset works")))

;; Test demo multi-block structures - REMOVED (demo.clj deleted)
;; Multi-block functionality is tested by other test functions

;; Test irregular multi-block
(defn test-irregular-multi-block []
  (log/info "Testing irregular multi-block...")
  (let [spec (bdsl/create-block-spec "test-irregular"
               {:multi-block? true
                :multi-block-positions [{:x 0 :y 0 :z 0}
                                        {:x 1 :y 0 :z 0}
                                        {:x 0 :y 1 :z 0}]
                :material :stone})]
    (assert (= (:multi-block? spec) true))
    (assert (= (:multi-block-positions spec) [{:x 0 :y 0 :z 0}
                                               {:x 1 :y 0 :z 0}
                                               {:x 0 :y 1 :z 0}]))
    (assert (nil? (:multi-block-size spec)))
    (log/info "✓ Irregular multi-block definition works")))

;; Test position normalization
(defn test-normalize-positions []
  (log/info "Testing position normalization...")
  (let [positions [{:x 5 :y 10 :z 3}
                   {:x 6 :y 10 :z 3}
                   {:x 5 :y 11 :z 3}]
        normalized (bdsl/normalize-positions positions)]
    (assert (= normalized [{:x 0 :y 0 :z 0}
                           {:x 1 :y 0 :z 0}
                           {:x 0 :y 1 :z 0}]))
    (log/info "✓ Position normalization works")))

;; Test irregular multi-block preset
(defn test-irregular-preset []
  (log/info "Testing irregular multi-block preset...")
  (let [positions [{:x 0 :y 0 :z 0} {:x 1 :y 0 :z 0} {:x 0 :y 1 :z 0}]
        preset (bdsl/irregular-multi-block-preset positions)]
    (assert (= (:multi-block? preset) true))
    (assert (some? (:multi-block-positions preset)))
    (assert (= (:material preset) :metal))
    (log/info "✓ Irregular multi-block preset works")))

;; Test shape helpers
(defn test-shape-helpers []
  (log/info "Testing shape helper functions...")
  ;; Test cross shape
  (let [cross (flatten (bdsl/create-cross-shape 2))]
    (assert (seq cross))
    (assert (some #(and (= (:x %) 0) (= (:y %) 0) (= (:z %) 0)) cross)))
  ;; Test L shape
  (let [l-shape (bdsl/create-l-shape 3 3)]
    (assert (= (count l-shape) 5))) ; 3 horizontal + 2 vertical (excluding overlap)
  ;; Test pyramid
  (let [pyramid (bdsl/create-pyramid-shape 5 3)]
    (assert (> (count pyramid) 0)))
  ;; Test hollow cube
  (let [hollow (bdsl/create-hollow-cube 5)]
    (assert (< (count hollow) (* 5 5 5)))) ; Less than solid cube
  (log/info "✓ Shape helper functions work"))

;; Test irregular validation
(defn test-irregular-validation []
  (log/info "Testing irregular multi-block validation...")
  ;; Should fail with empty positions
  (try
    (bdsl/validate-multi-block-positions [])
    (assert false "Should have thrown for empty positions")
    (catch Exception e
      (assert (.contains (.getMessage e) "cannot be empty"))))
  ;; Should fail without origin
  (try
    (bdsl/validate-multi-block-positions [{:x 1 :y 0 :z 0} {:x 2 :y 0 :z 0}])
    (assert false "Should have thrown for missing origin")
    (catch Exception e
      (assert (.contains (.getMessage e) "origin"))))
  ;; Should pass with valid positions
  (assert (bdsl/validate-multi-block-positions [{:x 0 :y 0 :z 0} {:x 1 :y 0 :z 0}]))
  (log/info "✓ Irregular multi-block validation works"))

;; Test demo irregular multi-blocks
(defn test-demo-irregular-blocks []
  (log/info "Testing demo irregular multi-block structures...")
  (assert (some? demo/cross-altar))
  (assert (some? demo/pyramid-shrine))
  (assert (= (:multi-block? demo/cross-altar) true))
  (assert (some? (:multi-block-positions demo/cross-altar)))
  (assert (= (count (:multi-block-positions demo/cross-altar)) 5)) ; center + 4 arms
  (log/info "✓ Demo irregular multi-block structures work"))

;; Run all tests
(defn run-all-tests []
  (log/info "=== Running Block DSL Tests ===")
  (try
    (test-basic-block)
    (test-block-registry)
    (test-defaults)
    (test-presets)
    (test-preset-merge)
    (test-validation)
    (test-handlers)
    (test-properties)
    (test-materials)
    (test-tool-types)
    (test-multi-block)
    (test-multi-block-positions)
    (test-multi-block-master-pos)
    (test-multi-block-validation)
    (test-multi-block-preset)
    (test-irregular-multi-block)
    (test-normalize-positions)
    (test-irregular-preset)
    (test-shape-helpers)
    (test-irregular-validation)
    (log/info "=== All Tests Passed! ===")
    true
    (catch Exception e
      (log/info "=== Test Failed! ===")
      (.printStackTrace e)
      false)))

;; Auto-run info
(defn init-tests! []
  (log/info "Block DSL tests available. Run (run-all-tests) to execute."))
