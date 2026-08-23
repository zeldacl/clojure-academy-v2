(ns cn.li.node.flow-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]
            [cn.li.node.flow :as flow]))

(use-fixtures :each
  (fn [f]
    (registry/reset-for-test!)
    (flow/install!)
    (f)
    (registry/reset-for-test!)))

(defn- dispatch-noop [_node ctx] ctx)

(deftest sequence-threads-binds-forward-test
  (let [program {:component :flow/sequence
                 :steps [{:component :data/bind :to :a :value 1.0}
                         {:component :data/bind :to :b :value {:expr :math/add :args [{:ref [:local :a]} 1.0]}}]}
        result (flow/execute! program {:locals {} :seed 0 :dispatch dispatch-noop})]
    (is (= 1.0 (get-in result [:locals :a])))
    (is (= 2.0 (get-in result [:locals :b])))))

(deftest branch-picks-then-or-else-test
  (let [program {:component :flow/branch :when true
                 :then {:component :data/bind :to :taken :value :then}
                 :else {:component :data/bind :to :taken :value :else}}]
    (is (= :then (get-in (flow/execute! program {:locals {} :seed 0 :dispatch dispatch-noop}) [:locals :taken])))))

(deftest branch-false-picks-else-test
  (let [program {:component :flow/branch :when false
                 :then {:component :data/bind :to :taken :value :then}
                 :else {:component :data/bind :to :taken :value :else}}]
    (is (= :else (get-in (flow/execute! program {:locals {} :seed 0 :dispatch dispatch-noop}) [:locals :taken])))))

(deftest foreach-does-not-leak-loop-locals-test
  (let [program {:component :flow/foreach :items [1.0 2.0 3.0] :as :item
                 :body {:component :data/bind :to :last-seen :value {:ref [:local :item]}}}
        result (flow/execute! program {:locals {} :seed 0 :dispatch dispatch-noop})]
    ;; The loop body's own scope never escapes: :item and :last-seen (bound
    ;; INSIDE each iteration's throwaway ctx) must not appear in the
    ;; returned locals, only whatever was true before the loop ran.
    (is (not (contains? (:locals result) :item)))
    (is (not (contains? (:locals result) :last-seen)))))

(deftest finish-short-circuits-sequence-test
  (let [program {:component :flow/sequence
                 :steps [{:component :flow/finish :outcome :done}
                         {:component :data/bind :to :should-not-run :value true}]}
        result (flow/execute! program {:locals {} :seed 0 :dispatch dispatch-noop})]
    (is (:finished? result))
    (is (= :done (:outcome result)))
    (is (not (contains? (:locals result) :should-not-run)))))

(deftest finish-defaults-finish-session-to-false-but-carries-it-when-set-test
  (let [default-result (flow/execute! {:component :flow/finish :outcome :done}
                                      {:locals {} :seed 0 :dispatch dispatch-noop})
        flagged-result (flow/execute! {:component :flow/finish :outcome :done :finish-session? true}
                                      {:locals {} :seed 0 :dispatch dispatch-noop})]
    (is (false? (:finish-session? default-result)))
    (is (true? (:finish-session? flagged-result)))))

(deftest dispatch-handles-unknown-component-test
  (let [seen (atom nil)
        program {:component :test/custom :x 1}
        dispatch (fn [node ctx] (reset! seen node) ctx)]
    (flow/execute! program {:locals {} :seed 0 :dispatch dispatch})
    (is (= :test/custom (:component @seen)))))
