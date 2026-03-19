(ns cn.li.mcmod.block.dsl-test
  "Unit tests for Block DSL"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.util.log :as log]))

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

;; (other tests unchanged... truncated for brevity in test file)
