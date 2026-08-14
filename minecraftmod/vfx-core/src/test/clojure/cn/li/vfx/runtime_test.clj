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
