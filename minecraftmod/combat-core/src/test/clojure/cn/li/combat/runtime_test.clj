(ns cn.li.combat.runtime-test
  (:require [clojure.test :refer :all]
            [cn.li.combat.registry :as registry]
            [cn.li.combat.dsl :as dsl]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.runtime :as runtime]
            [cn.li.mcmod.runtime.combat-contract :as contract]))

(use-fixtures :each (fn [f] (registry/reset-for-test!) (f)))

(deftest instant-ability-produces-neutral-plan
  (dsl/defability arc-gen
    {:id :test/arc-gen :activation :instant
     :program (dsl/sequence
                (dsl/damage {:amount 4.0 :type :electric})
                (dsl/vfx :test/arc {:event :impact :params {:width 1.0}}))
     :cost {:cp 3}})
  (let [catalog (compiler/compile-all!)
        engine (runtime/create-engine {:catalog catalog})
        result (runtime/dispatch-intent! engine "p1"
                                         {:intent-id 1 :op :start :ability-id :test/arc-gen})]
    (is (= :accepted (:status result)))
    (is (= 1 (count (:world-effects result))))
    (is (= :test/arc (get-in result [:vfx-signals 0 :effect-id])))
    (is (= :spawn (get-in result [:vfx-signals 0 :op])))
    (is (= [[:resource :cp -3.0]] (:state-patch result)))))

(deftest duplicate-intent-is-rejected
  (dsl/defability test-ability {:id :test/a :activation :instant :program (dsl/patch [])})
  (let [engine (runtime/create-engine {:catalog (compiler/compile-all!)})]
    (is (= :accepted (:status (runtime/dispatch-intent! engine "p" {:intent-id "x" :op :start :ability-id :test/a}))))
    (is (= :rejected (:status (runtime/dispatch-intent! engine "p" {:intent-id "x" :op :start :ability-id :test/a}))))))

(deftest session-is-deadline-driven
  (dsl/defability charging {:id :test/charging :activation :session :period-ticks 2
                             :program (dsl/vfx :test/charge {:event :pulse})})
  (let [engine (runtime/create-engine {:catalog (compiler/compile-all!) :now-tick (constantly 10)})]
    (is (= :accepted (:status (runtime/dispatch-intent! engine "p" {:intent-id 1 :op :start :ability-id :test/charging}))))
    (is (= 1 (count (runtime/tick! engine 12))))
    (is (= 1 (count (:sessions (runtime/snapshot-owner engine "p")))))
    (is (= "p" (:owner (first (runtime/tick! engine 14)))))))

(deftest query-result-flows-to-following-nodes
  (dsl/defability query-strike
    {:id :test/query-strike :activation :instant
     :program (dsl/sequence
                (dsl/raycast {:distance 12.0 :result-ref :hit})
                (dsl/require-hit)
                (dsl/damage {:amount 9.0 :type :electric :target-ref :hit}))})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :query-port {:raycast (fn [_ _] {:entity-id "target-1"})}})
        result (runtime/dispatch-intent! engine "p"
                                         {:intent-id 7 :op :start
                                          :ability-id :test/query-strike})]
    (is (= :accepted (:status result)))
    (is (= "target-1"
           (get-in result [:world-effects 0 :request :target])))
    (is (= :query (get-in result [:events 0 :type])))))

(deftest required-query-miss-does-not-consume-resource
  (dsl/defability query-miss
    {:id :test/query-miss :activation :instant :cost {:cp 4}
     :program (dsl/sequence
                (dsl/raycast {:distance 12.0})
                (dsl/require-hit)
                (dsl/damage {:amount 9.0 :type :electric}))})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :query-port {:raycast (fn [_ _] nil)}})
        result (runtime/dispatch-intent! engine "p"
                                         {:intent-id 8 :op :start
                                          :ability-id :test/query-miss})]
    (is (= :rejected (:status result)))
    (is (empty? (:state-patch result)))
    (is (= :required-condition-failed
           (get-in result [:feedback 0 :reason])))))

(deftest intent-contract-rejects-invalid-client-envelope
  (is (thrown? clojure.lang.ExceptionInfo
               (contract/intent {:schema-version 99 :intent-id 1
                                 :op :start :owner "p"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (contract/intent {:intent-id 1 :op :start :owner "p"
                                 :slot -1}))))

(deftest vfx-contract-normalizes-seed-and-rejects-bad-sequence
  (is (= 0 (:seed (contract/signal {:op :spawn :effect-id :e
                                    :instance-key [:i] :owner "p"
                                    :event-seq 1}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (contract/signal {:op :spawn :effect-id :e
                                 :instance-key [:i] :owner "p"
                                 :event-seq "1"}))))

(deftest damage-pipeline-is-deterministically-applied
  (dsl/defability amplified
    {:id :test/amplified :activation :instant
     :program (dsl/damage {:amount 10.0 :type :electric})})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :damage-pipeline [{:priority 10 :provider-id :test
                                    :ability-id :test/amplified :node-id :amp
                                    :run (fn [request _]
                                           (update request :base #(* 2.0 %)))}]})
        result (runtime/dispatch-intent! engine "p"
                                         {:intent-id 42 :op :start
                                          :ability-id :test/amplified})]
    (is (= 20.0 (get-in result [:world-effects 0 :request :base])))))

(deftest provider-custom-node-is-compiled-and-linked
  (registry/register-provider!
   {:provider-id :test/provider
    :revision 1
    :nodes [{:id :test/emit
             :revision 2
             :run (fn [_ _]
                    {:status :continue
                     :state-patch [[:ability-exp :test/custom 0.25]]})}]
    :abilities [{:id :test/custom :activation :instant
                 :program {:op :node :node-id :test/emit}}]})
  (let [catalog (compiler/compile-all!)
        engine (runtime/create-engine {:catalog catalog})
        result (runtime/dispatch-intent! engine "p"
                                         {:intent-id 43 :op :start
                                          :ability-id :test/custom})]
    (is (= :accepted (:status result)))
    (is (= [[:ability-exp :test/custom 0.25]] (:state-patch result)))))

(deftest data-expression-and-query-paths-stay-neutral
  (dsl/defability scaled-strike
    {:id :test/scaled-strike :activation :instant
     :program {:op :sequence
               :steps [{:op :query :query-type :attack :result-ref :attack
                        :result-paths {:target [:target-uuid]
                                       :impact [:impact]
                                       :victims [:victims]}}
                       {:op :branch :predicate-ref :target
                        :then {:op :damage :amount {:op :scale :min 10.0 :max 20.0}
                               :type :electric :target-ref :target}
                        :else {:op :patch :entries []}}
                       {:op :world-effect :effect-type :damage-aoe
                        :origin-ref :attack :origin-path [:impact]
                        :targets-ref :attack :targets-path [:victims]
                        :amount {:op :add :values [1.0 2.0]}
                        :damage-type :electric}]}})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :initial-owner-state (fn [_] {:ability-data {:skill-exps {:test/scaled-strike 0.5}}})
                 :query-port {:attack (fn [_ _]
                                        {:target-uuid "target-1"
                                         :impact {:x 1 :y 2 :z 3}
                                         :victims [{:uuid "target-2"}]})}})
        result (runtime/dispatch-intent! engine "p"
                                         {:intent-id 44 :op :start
                                          :ability-id :test/scaled-strike})]
    (is (= :accepted (:status result)))
    (is (= "target-1" (get-in result [:world-effects 0 :request :target])))
    (is (= 15.0 (get-in result [:world-effects 0 :request :base])))
    (is (= [{:uuid "target-2"}]
           (get-in result [:world-effects 1 :targets])))
    (is (= {:x 1 :y 2 :z 3}
           (get-in result [:world-effects 1 :origin])))))
