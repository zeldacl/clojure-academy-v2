(ns cn.li.vfx.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.runtime :as runtime]
            [cn.li.mcmod.runtime.vfx-contract :as contract]))

(defn test-effect []
  {:id :test/effect
   :init (fn [{:keys [params]}] {:value (long (or (:value params) 0))})
   :update (fn [state {:keys [events]}]
             (if (some #(= :stop (:event %)) events) nil (update state :value inc)))
   :bounds (fn [_ _] nil)
   :sample (fn [{:keys [sink state]}]
             ((:emit! sink) {:stage :world-after-translucent :primitive :billboard
                              :material :test/material :count (:value state)
                              :payload [(:value state)]}))})

(deftest tick-sample-and-owner-cleanup
  (let [rt (runtime/create-runtime)]
    (runtime/register-effect! rt (test-effect)) (runtime/freeze-registry! rt)
    (let [id (runtime/spawn! rt :test/effect {:owner :owner :params {:value 1}})]
      (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
      (is (= 1 (count (get-in (runtime/sample-frame! rt {:frame-id 1 :partial-tick 0.5})
                              [:stages :world-after-translucent]))))
      (runtime/signal! rt {:instance id} :stop {})
      (runtime/tick! rt {:tick-id 2 :delta-seconds 0.05})
      (is (empty? @(:instances rt))))))

(deftest ended-instance-releases-stable-key
  (let [rt (runtime/create-runtime)
        effect (assoc (test-effect)
                      :update (fn [_ _] nil))]
    (runtime/register-effect! rt effect)
    (runtime/freeze-registry! rt)
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :test/effect
                                   :instance-key [:session 1 :main]
                                   :owner :owner :event-seq 1 :params {}})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (is (nil? (runtime/instance-for-key rt [:session 1 :main])))
    ;; The same key can be reused after the descriptor ends.
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :test/effect
                                   :instance-key [:session 1 :main]
                                   :owner :owner :event-seq 2 :params {}})
    (is (some? (runtime/instance-for-key rt [:session 1 :main])))))

(deftest duplicate-frame-is-idempotent
  (let [rt (runtime/create-runtime)]
    (runtime/register-effect! rt (test-effect)) (runtime/freeze-registry! rt)
    (runtime/spawn! rt :test/effect {:owner :owner})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (is (identical? (runtime/sample-frame! rt {:frame-id 4 :partial-tick 0.0})
                    (runtime/sample-frame! rt {:frame-id 4 :partial-tick 1.0})))))

(deftest owner-and-world-indexed-cleanup
  (let [rt (runtime/create-runtime)]
    (runtime/register-effect! rt (test-effect))
    (runtime/freeze-registry! rt)
    (let [owner-id (runtime/spawn! rt :test/effect {:owner :player-a :world-id :world-a})
          world-id (runtime/spawn! rt :test/effect {:owner :player-b :world-id :world-a})
          other-id (runtime/spawn! rt :test/effect {:owner :player-b :world-id :world-b})]
      (runtime/clear-owner! rt :player-a)
      (is (nil? (get @(:instances rt) owner-id)))
      (is (= #{world-id other-id} (set (keys @(:instances rt)))))
      (runtime/clear-world! rt :world-a)
      (is (= #{other-id} (set (keys @(:instances rt)))))
      (is (empty? (get @(:owner-index rt) :player-a #{}))))))

(deftest deterministic-frame-digest
  (let [rt (runtime/create-runtime)]
    (runtime/register-effect! rt (test-effect))
    (runtime/freeze-registry! rt)
    (runtime/spawn! rt :test/effect {:owner :owner :params {:value 7} :seed 42})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (let [a (runtime/sample-frame! rt {:frame-id 3 :partial-tick 0.25})
          b (runtime/sample-frame! rt {:frame-id 3 :partial-tick 0.75})]
      (is (= (runtime/frame-digest a) (runtime/frame-digest b)))
      (is (= contract/schema-version (:schema-version a))))))

(deftest deterministic-replay-includes-payload
  (let [build-frame (fn []
                      (let [rt (runtime/create-runtime)]
                        (runtime/register-effect! rt (test-effect))
                        (runtime/freeze-registry! rt)
                        (runtime/spawn! rt :test/effect
                                        {:owner :owner :params {:value 7} :seed 42})
                        (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
                        (runtime/sample-frame! rt {:frame-id 9 :partial-tick 0.5})))]
    (is (= (runtime/frame-digest (build-frame))
           (runtime/frame-digest (build-frame))))))

(deftest latest-stage-survives-world-release
  (let [rt (runtime/create-runtime)]
    (runtime/register-effect! rt
      {:id :hand
       :init (fn [_] {})
       :update (fn [state _] state)
       :bounds (fn [_ _] nil)
       :sample (fn [{:keys [sink]}]
                 ((:emit! sink) {:stage :first-person :primitive :first-person
                                 :material :hand :count 1 :payload [{:tx 1.0}]}))})
    (runtime/freeze-registry! rt)
    (runtime/spawn! rt :hand {:owner :player})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (runtime/sample-frame! rt {:frame-id 1 :partial-tick 0.0})
    (runtime/release-frame! rt 1)
    (is (= [{:stage :first-person :primitive :first-person
             :material :hand :layout-version 1 :count 1 :sort-mode :stable
             :schema-version contract/schema-version :variant nil
             :payload [{:tx 1.0}]}]
           (runtime/latest-frame-stage rt :first-person)))))

(deftest abi-rejects-version-mismatch
  (testing "host API cannot silently cross ABI versions"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"schema version mismatch"
                          (contract/validate-host-api {:schema-version 99})))))

(deftest resource-reload-invalidates-frame
  (let [rt (runtime/create-runtime)]
    (runtime/register-effect! rt (test-effect))
    (runtime/freeze-registry! rt)
    (runtime/spawn! rt :test/effect {:owner :owner})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (runtime/sample-frame! rt {:frame-id 1 :partial-tick 0.0})
    (runtime/reload-resources! rt 7)
    (is (= 7 (runtime/resource-generation rt)))
    (is (nil? @(:last-frame rt)))))

(deftest interpolation-and-bounds-culling
  (let [samples (atom [])
        rt (runtime/create-runtime)
        effect {:id :sampled
                :init (fn [{:keys [params]}] {:value 0 :x (or (:x params) 0)})
                :update (fn [state _] (update state :value inc))
                :bounds (fn [{:keys [state]}]
                          {:center [(:x state) 0 0] :radius 0.0})
                :sample (fn [{:keys [sink interpolated-state]}]
                          (swap! samples conj interpolated-state)
                          ((:emit! sink) {:stage :world-after-translucent
                                          :primitive :billboard
                                          :material :test/material
                                          :count 1
                                          :payload [interpolated-state]}))}]
    (runtime/register-effect! rt effect)
    (runtime/freeze-registry! rt)
    (runtime/spawn! rt :sampled {:owner :near :params {:x 0}})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (let [frame (runtime/sample-frame! rt {:frame-id 1 :partial-tick 0.5
                                           :camera-pos [0 0 0]
                                           :view-distance 8.0})]
      (is (= [{:value 0.5 :x 0.0}] @samples))
      (is (= 1 (count (get-in frame [:stages :world-after-translucent])))))
    (reset! samples [])
    (runtime/spawn! rt :sampled {:owner :far :params {:x 100}})
    (runtime/tick! rt {:tick-id 2 :delta-seconds 0.05})
    (runtime/sample-frame! rt {:frame-id 2 :partial-tick 0.5
                               :camera-pos [0 0 0]
                               :view-distance 8.0})
    (is (= 1 (count @samples)))))

(deftest signal-queue-is-bounded
  (let [rt (runtime/create-runtime {:max-signals 3})]
    (dotimes [i 10] (runtime/signal! rt {:instance i} :event {:i i}))
    (is (= 3 (count @(:signals rt))))))

(deftest stable-key-signal-dispatch-is-idempotent
  (let [rt (runtime/create-runtime)
        seen (atom [])]
    (runtime/register-effect! rt
      {:id :stable/effect
       :init (fn [_] {:value 0})
       :update (fn [state {:keys [events]}]
                 (swap! seen conj events)
                 state)
       :bounds (fn [_ _] nil)
       :sample (fn [_] nil)})
    (runtime/freeze-registry! rt)
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :stable/effect
                                   :instance-key [:session 1 :main]
                                   :owner :owner :event-seq 1
                                   :params {}})
    (runtime/dispatch-signal! rt {:op :signal :effect-id :stable/effect
                                   :instance-key [:session 1 :main]
                                   :owner :owner :event-seq 1
                                   :event :duplicate :params {}})
    (runtime/dispatch-signal! rt {:op :signal :effect-id :stable/effect
                                   :instance-key [:session 1 :main]
                                   :owner :owner :event-seq 2
                                   :event :accepted :params {}})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (is (= [:accepted] (mapv :event (first @seen))))))

(deftest invalid-context-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (contract/tick-context {:tick-id 1 :delta-seconds -1.0})))
  (is (thrown? clojure.lang.ExceptionInfo
               (contract/frame-context {:frame-id 1 :partial-tick 2.0}))))
