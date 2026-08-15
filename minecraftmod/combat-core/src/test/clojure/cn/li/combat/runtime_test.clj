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
    (let [accepted (runtime/dispatch-intent! engine "p" {:intent-id "x" :op :start :ability-id :test/a})]
      (is (= :accepted (:status accepted)))
      (is (= "p" (:owner accepted)))
      (is (= "x" (:intent-id accepted))))
    (is (= :rejected (:status (runtime/dispatch-intent! engine "p" {:intent-id "x" :op :start :ability-id :test/a}))))))

(deftest intent-cache-is-bounded-per-owner
  (dsl/defability bounded {:id :test/bounded :activation :instant :program (dsl/patch [])})
  (let [engine (runtime/create-engine {:catalog (compiler/compile-all!)
                                       :max-seen-intents 2})]
    (doseq [intent-id [1 2 3]]
      (runtime/dispatch-intent! engine "p"
                                {:intent-id intent-id :op :start
                                 :ability-id :test/bounded}))
    (is (= [2 3] (get @(:seen-intents engine) "p")))
    (is (= :accepted
           (:status (runtime/dispatch-intent! engine "p"
                                              {:intent-id 1 :op :start
                                               :ability-id :test/bounded}))))))

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

(deftest damage-request-exposes-source-and-target-snapshots
  (let [seen (atom nil)
        engine (runtime/create-engine
                {:catalog {:abilities {} :nodes {} :content-hash "test"}
                 :initial-owner-state
                 (fn [owner]
                   {:owner owner
                    :active-abilities (if (= owner "victim") #{:guard} #{})})
                 :damage-pipeline
                 [{:priority 1 :provider-id :test :ability-id :test/guard
                   :node-id :observe
                   :run (fn [request context]
                          (reset! seen context)
                          request)}]})]
    (is (= 7.0 (:base (runtime/process-damage-request
                       engine {:source "attacker" :target "victim"
                                :base 7.0 :type :generic}))))
    (is (= "attacker" (:source @seen)))
    (is (= "victim" (:target @seen)))
    (is (= #{:guard} (get-in @seen [:target-state :active-abilities])))
    (is (= #{} (get-in @seen [:source-state :active-abilities])))))

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

(deftest phase-and-session-state-are-deadline-driven
  (dsl/defability phased
    {:id :test/phased :activation :session :period-ticks 2
     :program {:op :phase
               :start {:op :session-patch :entries [[[:started?] true]]}
               :pulse {:op :sequence
                       :steps [{:op :session-patch :entries [[[:pulsed?] true]]}
                               {:op :vfx :effect-id :test/pulse :event :pulse}]}
               :abort {:op :vfx :effect-id :test/pulse :event :abort}}})
  (let [engine (runtime/create-engine {:catalog (compiler/compile-all!)
                                       :now-tick (constantly 0)})
        started (runtime/dispatch-intent! engine "p"
                                          {:intent-id 45 :op :start
                                           :ability-id :test/phased})
        pulsed (first (runtime/tick! engine 2))
        aborted (runtime/dispatch-intent! engine "p"
                                          {:intent-id 46 :op :abort})]
    (is (= :accepted (:status started)))
    (is (empty? (:vfx-signals started)))
    (is (= :pulse (get-in pulsed [:vfx-signals 0 :event])))
    (is (= :accepted (:status aborted)))
    (is (= :abort (get-in aborted [:vfx-signals 0 :event])))
    (is (empty? (:sessions (runtime/snapshot-owner engine "p"))))))

(deftest session-cost-phase-keeps-start-and-release-free
  (dsl/defability pulse-cost
    {:id :test/pulse-cost :activation :session :period-ticks 1
     :cost-phase :pulse :cost {:cp 2}
     :program {:op :phase
               :start {:op :session-patch :entries [[[:started?] true]]}
               :pulse {:op :patch :entries []}
               :release {:op :patch :entries []}}})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :initial-owner-state (fn [_] {:resources {:cp 4}})})
        started (runtime/dispatch-intent! engine "p"
                                          {:intent-id 47 :op :start
                                           :ability-id :test/pulse-cost})
        started-state (runtime/snapshot-owner engine "p")
        pulsed (first (runtime/tick! engine 1))
        released (runtime/dispatch-intent! engine "p"
                                           {:intent-id 48 :op :release})]
    (is (empty? (:state-patch started)))
    (is (= [[[:started?] true]] (:session-patch started)))
    (is (= true (get-in started-state
                        [:sessions 0 :state :started?])))
    (is (= [[:resource :cp -2.0]] (:state-patch pulsed)))
    (is (empty? (:state-patch released)))))

(deftest overload-resource-is-authoritative-and-phase-aware
  (dsl/defability overload-pulse
    {:id :test/overload-pulse :activation :session :period-ticks 1
     :cost-phase :pulse :cost {:overload 4}
     :program {:op :phase
               :start {:op :patch :entries []}
               :pulse {:op :patch :entries []}
               :release {:op :patch :entries []}}})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :initial-owner-state (fn [_] {:resources {:overload 5}})})
        started (runtime/dispatch-intent! engine "p"
                                          {:intent-id 60 :op :start
                                           :ability-id :test/overload-pulse})
        pulsed (first (runtime/tick! engine 1))]
    (is (empty? (:state-patch started)))
    (is (= [[:resource :overload -4.0]] (:state-patch pulsed)))))

(deftest toggle-start-is-idempotent-and-activation-cost-is-separate
  (dsl/defability toggle-guard
    {:id :test/toggle-guard :activation :toggle :period-ticks 1
     :activation-cost {:overload 8.0}
     :cost-phase :pulse :cost {:cp 2.0}
     :program {:op :phase
               :start {:op :vfx :effect-id :test/guard :event :start}
               :pulse {:op :vfx :effect-id :test/guard :event :pulse}
               :abort {:op :vfx :effect-id :test/guard :event :end}}})
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :initial-owner-state (fn [_] {:resources {:cp 10.0 :overload 10.0}})})
        started (runtime/dispatch-intent! engine "p"
                                          {:intent-id 70 :op :start
                                           :ability-id :test/toggle-guard})
        stopped (runtime/dispatch-intent! engine "p"
                                          {:intent-id 71 :op :start
                                           :ability-id :test/toggle-guard})]
    (is (= [[:resource :overload -8.0]] (:state-patch started)))
    (is (= :abort (get-in stopped [:session-ops 0 :op])))
    (is (= :end (get-in stopped [:vfx-signals 0 :event])))
    (is (empty? (:sessions (runtime/snapshot-owner engine "p"))))))

(deftest session-counter-enforces-release-window
  (dsl/defability counted-session
    {:id :test/counted-session :activation :session :period-ticks 1
     :program {:op :phase
               :start {:op :session-patch
                       :entries [[[:charge-ticks] 0.0]]}
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 2.0}
                                 {:op :vfx :effect-id :test/counted
                                  :event :release}]}}})
  (let [engine (runtime/create-engine {:catalog (compiler/compile-all!)
                                       :now-tick (constantly 0)})
        started (runtime/dispatch-intent! engine "p"
                                          {:intent-id 50 :op :start
                                           :ability-id :test/counted-session})
        _ (runtime/tick! engine 1)
        _ (runtime/tick! engine 2)
        released (runtime/dispatch-intent! engine "p"
                                           {:intent-id 51 :op :release})]
    (is (= :accepted (:status started)))
    (is (= :accepted (:status released)))
    (is (= :release (get-in released [:vfx-signals 0 :event])))))

(deftest session-max-ticks-aborts-deterministically
  (dsl/defability bounded-session
    {:id :test/bounded-session :activation :session :period-ticks 1
     :max-session-ticks 0
     :program {:op :phase
               :abort {:op :vfx :effect-id :test/bounded
                       :event :abort}}})
  (let [engine (runtime/create-engine {:catalog (compiler/compile-all!)
                                       :now-tick (constantly 0)})
        _ (runtime/dispatch-intent! engine "p"
                                    {:intent-id 52 :op :start
                                     :ability-id :test/bounded-session})
        aborted (first (runtime/tick! engine 1))]
    (is (= :accepted (:status aborted)))
    (is (= :abort (get-in aborted [:vfx-signals 0 :event])))
    (is (empty? (:sessions (runtime/snapshot-owner engine "p"))))))

(deftest domain-state-handler-is-observed
  (let [engine (runtime/create-engine
                {:catalog {:abilities {} :content-hash "domain-state-test"}
                 :domain-event-handler
                 (fn [state event]
                   (if (= :mark (:type event))
                     (assoc-in state [:marks (:target event)] (:rate event))
                     state))})]
    (runtime/dispatch-domain-event! engine
                                    {:type :mark :target "victim" :rate 1.75})
    (is (= {"victim" 1.75}
           (get-in (runtime/domain-state engine) [:marks])))))

(deftest domain-event-resolves-target-owner-and-sequence
  (dsl/defability emits-domain-event
    {:id :test/emits-domain-event :activation :instant
     :program (dsl/sequence
                (dsl/raycast {:distance 8.0 :result-ref :hit})
                {:op :domain-event :event-type :radiation-mark
                 :target-ref :hit
                 :metadata {:duration 60 :rate {:op :scale :min 1.0 :max 2.0}}})})
  (let [catalog (compiler/compile-all!)
        engine (runtime/create-engine
                {:catalog catalog
                 :now-tick (constantly 7)
                 :initial-owner-state
                 (fn [_] {:ability-data {:skill-exps {:test/emits-domain-event 0.5}}})
                 :query-port {:raycast (fn [_ _] {:uuid "victim"})}})
        result (runtime/dispatch-intent!
                engine "owner"
                {:intent-id 91 :op :start
                 :ability-id :test/emits-domain-event})
        event (some #(when (= :radiation-mark (:type %)) %) (:events result))]
    (is (= :accepted (:status result)))
    (is (= :radiation-mark (:type event)))
    (is (= "owner" (:owner event)))
    (is (= "victim" (:target-id event)))
    (is (= 7 (:tick event)))
    (is (= 1.5 (get-in event [:metadata :rate])))))

(deftest domain-event-identity-is-idempotent
  (let [calls (atom 0)
        engine (runtime/create-engine
                {:catalog {:abilities {} :content-hash "domain-idempotency"}
                 :domain-event-handler
                 (fn [state _]
                   (swap! calls inc)
                   (update state :count (fnil inc 0)))})
        event {:type :radiation-mark :owner "p" :intent-id 9
               :session-id "s" :event-seq 4}]
    (runtime/dispatch-domain-event! engine event)
    (runtime/dispatch-domain-event! engine event)
    (is (= 1 @calls))
    (is (= 1 (get (runtime/domain-state engine) :count)))))
