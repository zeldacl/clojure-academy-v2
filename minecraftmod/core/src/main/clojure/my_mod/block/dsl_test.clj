(ns my-mod.block.dsl-test
  "Unit tests for Block DSL"
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.demo :as demo]
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

;; Test demo blocks
(defn test-demo-blocks []
  (log/info "Testing demo blocks...")
  (assert (some? demo/demo-block))
  (assert (some? demo/copper-ore))
  (assert (some? demo/glowing-stone))
  (assert (= (:id demo/demo-block) "demo-block"))
  (assert (= (:material demo/copper-ore) :stone))
  (assert (= (:light-level demo/glowing-stone) 15))
  (log/info "✓ Demo blocks work"))

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
    (test-demo-blocks)
    (test-materials)
    (test-tool-types)
    (log/info "=== All Tests Passed! ===")
    true
    (catch Exception e
      (log/info "=== Test Failed! ===")
      (.printStackTrace e)
      false)))

;; Auto-run info
(defn init-tests! []
  (log/info "Block DSL tests available. Run (run-all-tests) to execute."))
