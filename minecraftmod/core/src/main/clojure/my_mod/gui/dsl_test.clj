(ns my-mod.gui.dsl-test
  "Unit tests for GUI DSL"
  (:require [my-mod.gui.dsl :as dsl]
            [my-mod.util.log :as log]))

;; Test basic GUI definition
(defn test-basic-gui []
  (log/info "Testing basic GUI definition...")
  (let [test-gui (dsl/create-gui-spec "test-gui"
                   {:title "Test"
                    :width 176
                    :height 166
                    :slots [{:index 0 :x 10 :y 10}]
                    :buttons [{:id 0 :x 20 :y 20 :text "Test"}]})]
    (assert (= (:id test-gui) "test-gui"))
    (assert (= (:title test-gui) "Test"))
    (assert (= (count (:slots test-gui)) 1))
    (assert (= (count (:buttons test-gui)) 1))
    (log/info "✓ Basic GUI definition works")))

;; Test GUI registry
(defn test-gui-registry []
  (log/info "Testing GUI registry...")
  (dsl/defgui registry-test-gui
    :title "Registry Test"
    :slots [{:index 0 :x 0 :y 0}])
  (assert (contains? @dsl/gui-registry "registry-test-gui"))
  (assert (= (:id (dsl/get-gui "registry-test-gui")) "registry-test-gui"))
  (log/info "✓ GUI registry works"))

;; Test slot filters
(defn test-slot-filters []
  (log/info "Testing slot filters...")
  (let [any-filter dsl/any-item-filter
        mock-item (reify Object
                    (toString [_] "MockItem"))]
    (assert (any-filter mock-item))
    (log/info "✓ Slot filters work")))

;; Test slot change handlers
(defn test-slot-change-handlers []
  (log/info "Testing slot change handlers...")
  (let [test-slots (atom {})
        handler (dsl/slot-change-handler test-slots 0)
        mock-old nil
        mock-new "TestItem"]
    (handler mock-old mock-new)
    (assert (= (get @test-slots 0) mock-new))
    (log/info "✓ Slot change handlers work")))

;; Test button handlers
(defn test-button-handlers []
  (log/info "Testing button handlers...")
  (let [test-slots (atom {0 "Item"})
        handler (dsl/clear-slot-handler test-slots 0)]
    (handler)
    (assert (nil? (get @test-slots 0)))
    (log/info "✓ Button handlers work")))

;; Test GUI instance creation
(defn test-gui-instance []
  (log/info "Testing GUI instance creation...")
  (let [gui-spec (dsl/get-gui "demo-gui")
        mock-player "TestPlayer"
        mock-world "TestWorld"
        mock-pos [0 0 0]
        instance (dsl/create-gui-instance gui-spec mock-player mock-world mock-pos)]
    (assert (= (:player instance) mock-player))
    (assert (= (:world instance) mock-world))
    (assert (= (:pos instance) mock-pos))
    (assert (map? @(get-in instance [:data :slots])))
    (log/info "✓ GUI instance creation works")))

;; Test slot state management
(defn test-slot-state []
  (log/info "Testing slot state management...")
  (let [gui-spec (dsl/get-gui "demo-gui")
        instance (dsl/create-gui-instance gui-spec "Player" "World" [0 0 0])
        test-item "TestItem"]
    (dsl/set-slot-state! instance 0 test-item)
    (assert (= (dsl/get-slot-state instance 0) test-item))
    (dsl/clear-slot-state! instance 0)
    (assert (nil? (dsl/get-slot-state instance 0)))
    (log/info "✓ Slot state management works")))

;; Test button state management
(defn test-button-state []
  (log/info "Testing button state management...")
  (let [gui-spec (dsl/get-gui "demo-gui")
        instance (dsl/create-gui-instance gui-spec "Player" "World" [0 0 0])]
    (assert (dsl/button-enabled? instance 0))
    (dsl/set-button-enabled! instance 0 false)
    (assert (not (dsl/button-enabled? instance 0)))
    (dsl/set-button-enabled! instance 0 true)
    (assert (dsl/button-enabled? instance 0))
    (log/info "✓ Button state management works")))

;; Test demo GUIs - REMOVED (gui/demo.clj deleted)
;; Demo GUIs were example implementations, core DSL functionality
;; is tested by other test functions in this file

;; Test validation
(defn test-validation []
  (log/info "Testing GUI validation...")
  (try
    (dsl/create-gui-spec nil {:title "Test"})
    (assert false "Should have thrown exception")
    (catch Exception e
      (assert (.contains (.getMessage e) "must have an :id"))))
  
  (try
    (dsl/create-gui-spec "test" {:slots [{:x 10 :y 10}]})
    (assert false "Should have thrown exception for missing slot index")
    (catch Exception e
      (assert (.contains (.getMessage e) "must have an :index"))))
  
  (log/info "✓ GUI validation works"))

;; Test processing handler
(defn test-processing-handler []
  (log/info "Testing processing handler...")
  (let [test-slots (atom {0 "Input1" 1 "Input2"})
        process-fn (fn [in1 in2] (str in1 "+" in2))
        handler (dsl/processing-handler test-slots [0 1] 2 process-fn)]
    (handler)
    (assert (= (get @test-slots 2) "Input1+Input2"))
    (assert (nil? (get @test-slots 0)))
    (assert (nil? (get @test-slots 1)))
    (log/info "✓ Processing handler works")))

;; Run all tests
(defn run-all-tests []
  (log/info "=== Running GUI DSL Tests ===")
  (try
    (test-basic-gui)
    (test-gui-registry)
    (test-slot-filters)
    (test-slot-change-handlers)
    (test-button-handlers)
    (test-gui-instance)
    (test-slot-state)
    (test-button-state)
    (test-validation)
    (test-processing-handler)
    (log/info "=== All Tests Passed! ===")
    true
    (catch Exception e
      (log/info "=== Test Failed! ===")
      (.printStackTrace e)
      false)))

;; Auto-run tests when namespace is loaded
(defn init-tests! []
  (log/info "GUI DSL tests available. Run (run-all-tests) to execute."))
