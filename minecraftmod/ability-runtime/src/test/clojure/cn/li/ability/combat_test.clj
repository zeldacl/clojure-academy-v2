(ns cn.li.ability.combat-test
  "beam/settle! depends on cn.li.mcmod.platform.raycast/available?, which
   reads the mcmod Framework atom -- unavailable in a bare ability-runtime
   test, so settle! would short-circuit to nil regardless of payload. These
   tests stub it out with with-redefs to isolate what actually changed in
   P4.2: execute-result! dispatch by content-id, not beam physics.

   with-redefs only rebinds for the dynamic extent of its body, not for the
   lifetime of any closure created inside it -- create-runtime's :execute!
   closure calls beam/settle! later, when invoked, not when created, so the
   invocation itself must also be inside the with-redefs body or the real
   (Framework-less, always-nil) settle! runs instead."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.combat :as combat]
            [cn.li.combat.beam-settlement :as beam]))

(deftest execute-result-dispatches-to-owning-content-id-test
  (with-redefs [beam/settle! (fn [payload] {:status :applied :payload payload})]
    (let [calls (atom [])
          runtime (combat/create-runtime
                   {:execute-result! {:ac (fn [owner result] (swap! calls conj [:ac owner result]))
                                      :bc (fn [owner result] (swap! calls conj [:bc owner result]))}
                    :content-id-for {:ac-ability :ac :bc-ability :bc}})]
      ((:execute! runtime) "owner-1" {:ability-id :bc-ability :world-id "w"})
      (is (= [[:bc "owner-1" {:status :applied :payload {:ability-id :bc-ability :world-id "w"}}]]
             @calls)))))

(deftest execute-result-with-unknown-ability-id-settles-but-notifies-no-one-test
  (with-redefs [beam/settle! (fn [payload] {:status :applied :payload payload})]
    (let [calls (atom [])
          runtime (combat/create-runtime
                   {:execute-result! {:ac (fn [owner result] (swap! calls conj [:ac owner result]))}
                    :content-id-for {:ac-ability :ac}})
          result ((:execute! runtime) "owner-1" {:ability-id :unregistered-ability})]
      (is (empty? @calls))
      (is (= {:status :applied :payload {:ability-id :unregistered-ability}} result)))))

(deftest execute-result-with-no-content-id-for-still-settles-test
  (with-redefs [beam/settle! (fn [payload] {:status :applied :payload payload})]
    (let [runtime (combat/create-runtime {:execute-result! {:ac (fn [_ _] (throw (ex-info "should not be called" {})))}})
          result ((:execute! runtime) "owner-1" {:ability-id :ac-ability})]
      (is (= :applied (:status result))))))
