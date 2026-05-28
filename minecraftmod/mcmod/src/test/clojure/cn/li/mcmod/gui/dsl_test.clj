(ns cn.li.mcmod.gui.dsl-test
  "Unit tests for GUI DSL"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.gui.dsl :as dsl]
            [cn.li.mcmod.gui.registry :as gui-registry]))

(defn- with-fresh-gui-registry-runtime [f]
  (binding [gui-registry/*gui-registry-runtime* (gui-registry/create-gui-registry-runtime)]
    (f)))

(use-fixtures :each with-fresh-gui-registry-runtime)

(defn- platform-gui-spec
  [id gui-id gui-type]
  (dsl/create-gui-spec id
                       {:gui-id gui-id
                        :registration {:display-name id
                                       :gui-type gui-type
                                       :registry-name (str id "_registry")
                                       :screen-factory-fn-kw (keyword (str "create-" id "-screen"))}}))

;; Test basic GUI definition
(deftest basic-gui-test
  (testing "basic GUI definition"
    (let [test-gui (dsl/create-gui-spec "test-gui"
                                        {:title "Test"
                                         :width 176
                                         :height 166
                                         :slots [{:index 0 :x 10 :y 10}]
                                         :buttons [{:id 0 :x 20 :y 20 :text "Test"}]})]
      (is (= (:id test-gui) "test-gui"))
      (is (= "Test" (get-in test-gui [:layout :title])))
      (is (= 1 (count (get-in test-gui [:layout :slots]))))
      (is (= 1 (count (get-in test-gui [:layout :buttons])))))))

;; Test GUI registry
(deftest gui-registry-test
  (testing "GUI registry stores and resolves by id"
    (gui-registry/register-gui!
      (dsl/create-gui-spec "registry-test-gui"
                           {:title "Registry Test"
                            :slots [{:index 0 :x 0 :y 0}]}))
    (is (contains? (set (gui-registry/list-guis)) "registry-test-gui"))
    (is (= "registry-test-gui" (:id (gui-registry/get-gui "registry-test-gui"))))))

(deftest gui-registry-platform-id-test
  (testing "GUI registry stores platform-visible GUI metadata"
      (gui-registry/register-gui! (platform-gui-spec "platform-a" 42 :platform-a))
      (is (= "platform-a" (:id (gui-registry/get-gui-by-gui-id 42))))
      (is (= :platform-a (gui-registry/get-gui-type 42)))
      (is (= 42 (gui-registry/get-gui-id-for-type :platform-a))))

  (testing "duplicate logical GUI ids fail fast"
      (gui-registry/register-gui! (platform-gui-spec "dup-logical" 43 :dup-logical-a))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Duplicate GUI registry id"
        (gui-registry/register-gui! (platform-gui-spec "dup-logical" 44 :dup-logical-b)))))

  (testing "duplicate platform GUI ids fail fast"
      (gui-registry/register-gui! (platform-gui-spec "dup-platform-a" 45 :dup-platform-a))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Duplicate platform GUI id"
        (gui-registry/register-gui! (platform-gui-spec "dup-platform-b" 45 :dup-platform-b))))))

;; Test slot filters
(deftest slot-filters-test
  (testing "slot filter defaults to allow all"
    (let [any-filter dsl/any-item-filter
      mock-item (reify Object
          (toString [_] "MockItem"))]
  (is (true? (any-filter mock-item))))))

;; Test slot change handlers
(deftest slot-change-handler-test
  (testing "slot change handler updates state atom"
  (let [test-slots (atom {})
      handler (dsl/slot-change-handler test-slots 0)
      mock-old nil
      mock-new "TestItem"]
    (handler mock-old mock-new)
    (is (= mock-new (get @test-slots 0))))))

;; Test button handlers
(deftest button-handler-test
  (testing "clear slot handler clears target slot"
  (let [test-slots (atom {0 "Item"})
      handler (dsl/clear-slot-handler test-slots 0)]
    (handler)
    (is (nil? (get @test-slots 0))))))

;; Test GUI instance creation
(deftest gui-instance-test
  (testing "GUI instance creation"
  (let [gui-spec (dsl/create-gui-spec "instance-test-gui"
                    {:title "Instance Test"
                     :slots [{:index 0 :x 0 :y 0}]
                     :buttons [{:id 0 :x 0 :y 0 :text "Btn"}]})
      mock-player "TestPlayer"
      mock-world "TestWorld"
      mock-pos [0 0 0]
      instance (dsl/create-gui-instance gui-spec mock-player mock-world mock-pos)]
    (is (= mock-player (:player instance)))
    (is (= mock-world (:world instance)))
    (is (= mock-pos (:pos instance)))
    (is (map? @(get-in instance [:data :slots]))))))

;; Test slot state management
(deftest slot-state-test
  (testing "slot state can set and clear"
  (let [gui-spec (dsl/create-gui-spec "slot-state-test-gui"
                    {:title "Slot State Test"
                     :slots [{:index 0 :x 0 :y 0}]})
      instance (dsl/create-gui-instance gui-spec "Player" "World" [0 0 0])
      test-item "TestItem"]
    (dsl/set-slot-state! instance 0 test-item)
    (is (= test-item (dsl/get-slot-state instance 0)))
    (dsl/clear-slot-state! instance 0)
    (is (nil? (dsl/get-slot-state instance 0))))))

;; Test button state management
(deftest button-state-test
  (testing "button enabled state toggles"
  (let [gui-spec (dsl/create-gui-spec "button-state-test-gui"
                    {:title "Button State Test"
                     :buttons [{:id 0 :x 0 :y 0 :text "Btn"}]})
      instance (dsl/create-gui-instance gui-spec "Player" "World" [0 0 0])]
    (is (true? (dsl/button-enabled? instance 0)))
    (dsl/set-button-enabled! instance 0 false)
    (is (false? (dsl/button-enabled? instance 0)))
    (dsl/set-button-enabled! instance 0 true)
    (is (true? (dsl/button-enabled? instance 0))))))

;; Test demo GUIs - REMOVED (gui/demo.clj deleted)
;; Demo GUIs were example implementations, core DSL functionality
;; is tested by other test functions in this file

;; Test validation
(deftest validation-test
  (testing "invalid id is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"non-empty string :id"
          (dsl/create-gui-spec nil {:title "Test"}))))
  (testing "slot index is required"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":index"
          (dsl/create-gui-spec "test" {:slots [{:x 10 :y 10}]})))))

;; Test processing handler
(deftest processing-handler-test
  (testing "processing handler consumes inputs and writes output"
  (let [test-slots (atom {0 "Input1" 1 "Input2"})
      process-fn (fn [in1 in2] (str in1 "+" in2))
      handler (dsl/processing-handler test-slots [0 1] 2 process-fn)]
    (handler)
    (is (= "Input1+Input2" (get @test-slots 2)))
    (is (nil? (get @test-slots 0)))
    (is (nil? (get @test-slots 1))))))
