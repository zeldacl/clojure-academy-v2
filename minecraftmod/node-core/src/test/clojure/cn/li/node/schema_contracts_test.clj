(ns cn.li.node.schema-contracts-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.schema :as schema]
            [cn.li.node.contracts :as contracts]))

(deftest final-type-algebra-test
  (testing "all editor-visible type families are closed and assignable"
    (doseq [t [:bool :int :float :string :resource-id :vec3 :unit-vec3
               :entity-ref :hit-result
               (schema/list-type :float)
               (schema/option-type :entity-ref)
               (schema/handle-type :fire-ring)
               (schema/record-type :custom-record)]]
      (is (schema/type? t) (str "not a type: " t)))
    (is (schema/compatible? :float :int))
    (is (not (schema/compatible? :int :float)))))

(deftest descriptor-validation-test
  (is (= :query (:kind (schema/validate-descriptor
                        {:id :target/raycast :kind :query
                         :inputs {:shape {:type :query-shape}}
                         :outputs {:hit {:type :hit-result}}
                         :effects #{:read}}))))
  (is (try
        (schema/validate-descriptor {:id :bad :kind :query
                                      :inputs {:x {:type :not-a-type}}})
        false
        (catch clojure.lang.ExceptionInfo _ true))))

(deftest multi-owner-transaction-test
  (let [txn (-> (contracts/state-txn-set
                 {:alice {:revision 7 :state {:energy 10}}
                  :bob {:revision 2 :state {:energy 4}}})
                (contracts/update-owner-in :alice [:energy] #(- % 3))
                (contracts/assoc-owner-in :bob [:energy] 9))]
    (is (= 7 (get-in txn [:owners :alice :base-revision])))
    (is (= 7 (get-in txn [:owners :alice :working-state :energy])))
    (is (= 9 (get (contracts/owner-state txn :bob) :energy)))
    (is (= #{:alice :bob} (set (map :owner (contracts/txn-entries txn)))))))
