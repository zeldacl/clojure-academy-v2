(ns cn.li.combat.structural-primitives-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.structural-primitives :as structural]
            [cn.li.combat.policy-primitives :as policy]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (node-flow/install!)
    (policy/install!)
    (structural/install!)
    (f)
    (node/reset-for-test!)))

(defn- base-ctx []
  {:locals {} :seed 0 :dispatch structural/dispatch})

;; --- :flow/phases ---------------------------------------------------------

(deftest phases-dispatches-to-current-phase-test
  (let [program {:component :flow/phases
                 :start {:component :data/bind :to :which :value :start}
                 :pulse {:component :data/bind :to :which :value :pulse}}
        result (node-flow/execute! program (assoc (base-ctx) :phase :pulse))]
    (is (= :pulse (get-in result [:locals :which])))))

(deftest phases-dispatches-into-events-by-event-name-test
  (let [program {:component :flow/phases
                 :events {:coin-thrown {:component :data/bind :to :which :value :coin}}}
        result (node-flow/execute! program (assoc (base-ctx) :phase :events :event :coin-thrown))]
    (is (= :coin (get-in result [:locals :which])))))

(deftest phases-missing-child-is-a-noop-test
  (let [program {:component :flow/phases :start {:component :data/bind :to :which :value :start}}
        result (node-flow/execute! program (assoc (base-ctx) :phase :abort))]
    (is (not (contains? (:locals result) :which)))))

;; --- :flow/window -----------------------------------------------------

(deftest window-passes-value-inside-range-test
  (let [program {:component :flow/window :value 5.0 :min-exclusive 0.0 :max-inclusive 10.0
                 :on-pass {:component :data/bind :to :taken :value :pass}
                 :on-fail {:component :data/bind :to :taken :value :fail}}]
    (is (= :pass (get-in (node-flow/execute! program (base-ctx)) [:locals :taken])))))

(deftest window-fails-value-outside-range-test
  (let [program {:component :flow/window :value 50.0 :min-exclusive 0.0 :max-inclusive 10.0
                 :on-pass {:component :data/bind :to :taken :value :pass}
                 :on-fail {:component :data/bind :to :taken :value :fail}}]
    (is (= :fail (get-in (node-flow/execute! program (base-ctx)) [:locals :taken])))))

(deftest window-exclusive-lower-bound-test
  (let [program {:component :flow/window :value 0.0 :min-exclusive 0.0 :max-inclusive 10.0
                 :on-pass {:component :data/bind :to :taken :value :pass}
                 :on-fail {:component :data/bind :to :taken :value :fail}}]
    (is (= :fail (get-in (node-flow/execute! program (base-ctx)) [:locals :taken]))
        ":min-exclusive means the lower bound itself does not pass")))

;; --- :flow/once -------------------------------------------------------

(deftest once-runs-on-first-then-on-duplicate-test
  (let [program {:component :flow/once :key :projectile-1
                 :on-first {:component :data/bind :to :taken :value :first}
                 :on-duplicate {:component :data/bind :to :taken :value :dup}}
        latches* (atom #{})
        ctx1 (assoc (base-ctx) :latches* latches*)
        result1 (node-flow/execute! program ctx1)
        result2 (node-flow/execute! program (assoc ctx1 :locals {}))]
    (is (= :first (get-in result1 [:locals :taken])))
    (is (= :dup (get-in result2 [:locals :taken])))))

(deftest once-different-keys-both-run-on-first-test
  (let [program {:component :flow/once :key {:ref [:local :id]}
                 :on-first {:component :data/bind :to :taken :value :first}
                 :on-duplicate {:component :data/bind :to :taken :value :dup}}
        latches* (atom #{})
        ctx (assoc (base-ctx) :latches* latches*)
        result-a (node-flow/execute! program (assoc ctx :locals {:id :a}))
        result-b (node-flow/execute! program (assoc ctx :locals {:id :b}))]
    (is (= :first (get-in result-a [:locals :taken])))
    (is (= :first (get-in result-b [:locals :taken])))))

;; --- :txn/atomic --------------------------------------------------------

(defn- txn-program [guard-passes?]
  {:component :txn/atomic
   :guards [{:component :guard/value-in :value (if guard-passes? :fire :water) :one-of [:fire]}]
   :reservations [{:component :data/bind :to :reserved :value true}]
   :body {:component :data/bind :to :body-ran :value true}
   :on-success {:component :data/bind :to :success-ran :value true}
   :on-fail {:component :data/bind :to :fail-ran :value true}})

(deftest txn-atomic-runs-body-and-on-success-when-guards-pass-test
  (let [result (node-flow/execute! (txn-program true) (base-ctx))]
    (is (true? (get-in result [:locals :reserved])))
    (is (true? (get-in result [:locals :body-ran])))
    (is (true? (get-in result [:locals :success-ran])))
    (is (not (contains? (:locals result) :fail-ran)))))

(deftest txn-atomic-runs-on-fail-and-skips-reservations-when-guards-fail-test
  (let [result (node-flow/execute! (txn-program false) (base-ctx))]
    (is (not (contains? (:locals result) :reserved)))
    (is (not (contains? (:locals result) :body-ran)))
    (is (true? (get-in result [:locals :fail-ran])))))

(deftest txn-atomic-without-on-success-returns-bodys-own-bindings-test
  (let [program (dissoc (txn-program true) :on-success)
        result (node-flow/execute! program (base-ctx))]
    (is (true? (get-in result [:locals :body-ran])))))

;; --- dispatch: leaf primitives route through invoke-primitive! + :bind ---

(deftest dispatch-executes-leaf-primitive-and-binds-its-output-test
  (let [program {:component :guard/value-in :value :fire :one-of [:fire :ice] :bind {:result :matched}}
        result (structural/dispatch program (base-ctx))]
    (is (true? (get-in result [:locals :matched])))))

(deftest dispatch-resolves-refs-in-leaf-inputs-test
  (let [program {:component :flow/sequence
                 :steps [{:component :data/bind :to :one-of :value [:fire :ice]}
                         {:component :guard/value-in :value :ice :one-of {:ref [:local :one-of]}
                          :bind {:result :matched}}]}
        result (node-flow/execute! program (base-ctx))]
    (is (true? (get-in result [:locals :matched])))))
