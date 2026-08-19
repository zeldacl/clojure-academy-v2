(ns cn.li.combat.dataflow-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.components :as components]
            [cn.li.combat.dataflow :as dataflow]))

(defn- setup! []
  (components/reset-for-test!)
  (components/register-builtins!))

(deftest reading-a-never-bound-slot-is-rejected
  (setup!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not definitely bound"
       (dataflow/check-program!
        {:component :flow/sequence
         :steps [{:component :domain/event :event-type :x
                   :payload {:position {:ref [:slot :never-bound]}}}]}))))

(deftest reading-a-forward-bound-slot-in-the-same-sequence-passes
  (setup!)
  (is (nil? (dataflow/check-program!
             {:component :flow/sequence
              :steps [{:component :target/raycast
                       :origin {:x 0} :direction {:x 0} :distance 1.0
                       :result :aim-hit}
                      {:component :domain/event :event-type :x
                       :payload {:position {:ref [:slot :aim-hit :position]}}}]}))))

(deftest reading-a-slot-before-it-is-bound-is-rejected
  (setup!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not definitely bound"
       (dataflow/check-program!
        {:component :flow/sequence
         :steps [{:component :domain/event :event-type :x
                   :payload {:position {:ref [:slot :aim-hit :position]}}}
                  {:component :target/raycast
                   :origin {:x 0} :direction {:x 0} :distance 1.0
                   :result :aim-hit}]}))))

(deftest slot-bound-on-both-branches-is-visible-after-the-branch
  (setup!)
  (is (nil? (dataflow/check-program!
             {:component :flow/sequence
              :steps [{:component :flow/branch
                       :when true
                       :then {:component :data/bind :to :award :value 1.0}
                       :else {:component :data/bind :to :award :value 2.0}}
                      {:component :domain/event :event-type :x
                       :payload {:amount {:ref [:slot :award]}}}]}))))

(deftest slot-bound-on-only-one-branch-is-not-visible-after-the-branch
  (testing "this is the exact shape of the arc-gen :exp-award pattern, deliberately made fragile"
    (setup!)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not definitely bound"
         (dataflow/check-program!
          {:component :flow/sequence
           :steps [{:component :flow/branch
                    :when true
                    :then {:component :data/bind :to :award :value 1.0}
                    :else {:component :flow/sequence :steps []}}
                   {:component :domain/event :event-type :x
                    :payload {:amount {:ref [:slot :award]}}}]})))))

(deftest slot-bound-inside-foreach-body-does-not-leak-after-the-loop
  (setup!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not definitely bound"
       (dataflow/check-program!
        {:component :flow/sequence
         :steps [{:component :flow/foreach
                  :items [] :as :item :limit 0
                  :body {:component :data/bind :to :leaked :value 1.0}}
                 {:component :domain/event :event-type :x
                  :payload {:amount {:ref [:slot :leaked]}}}]}))))

(deftest flow-control-outside-a-loop-is-rejected
  (setup!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #":flow/foreach body"
       (dataflow/check-program!
        {:component :flow/sequence
         :steps [{:component :flow/control :signal :skip-item}]}))))

(deftest flow-control-inside-a-loop-passes
  (setup!)
  (is (nil? (dataflow/check-program!
             {:component :flow/foreach
              :items [] :as :item :limit 0
              :body {:component :flow/control :signal :skip-item}}))))

(deftest unknown-component-is-rejected
  (setup!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown component"
       (dataflow/check-program!
        {:component :not/a-real-component}))))

(deftest txn-atomic-without-on-success-propagates-body-bindings
  (setup!)
  (is (nil? (dataflow/check-program!
             {:component :flow/sequence
              :steps [{:component :txn/atomic
                       :guards [] :reservations []
                       :body {:component :data/bind :to :cost :value 1.0}
                       :on-fail {:component :flow/finish :outcome :fail}}
                      {:component :domain/event :event-type :x
                       :payload {:amount {:ref [:slot :cost]}}}]}))))
