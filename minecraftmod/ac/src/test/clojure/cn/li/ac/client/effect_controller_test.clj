(ns cn.li.ac.client.effect-controller-test
  "Headless tests for the final stable-key VFX composition root."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.client.effect-controller :as controller]))

(defn- final-catalog []
  {:effects
   {:probe-transient
    {:id :probe-transient
     :lifecycle :transient
     :state-slots {}
     :control-graph {:duration-ticks 4}}}})

(defn- reset-runtime! []
  (controller/reset-for-test!)
  (controller/register-catalog! (final-catalog))
  (controller/freeze!))

(deftest stable-key-creates-independent-final-instances-test
  (reset-runtime!)
  (controller/dispatch-signal!
   {:op :spawn :effect-id :probe-transient :owner "owner-1"
    :world-id "world" :instance-key [:cast 1] :event-seq 1
    :params {:duration-ticks 4}})
  (controller/dispatch-signal!
   {:op :spawn :effect-id :probe-transient :owner "owner-1"
    :world-id "world" :instance-key [:cast 2] :event-seq 1
    :params {:duration-ticks 4}})
  (is (= 2 (count @(:instances (controller/runtime)))))
  (controller/clear-owner! "owner-1")
  (is (empty? @(:instances (controller/runtime)))))

(deftest stale-final-update-does-not-overwrite-parameters-test
  (reset-runtime!)
  (let [signal {:op :spawn :effect-id :probe-transient :owner "owner-1"
                :world-id "world" :instance-key [:cast 3] :event-seq 10
                :params {:duration-ticks 4 :value 1.0}}]
    (controller/dispatch-signal! signal)
    (controller/dispatch-signal!
     (assoc signal :op :update :event-seq 11 :params {:value 2.0}))
    (controller/dispatch-signal!
     (assoc signal :op :update :event-seq 9 :params {:value 99.0}))
    (let [instance (first (vals @(:instances (controller/runtime))))]
      (is (= 11 (:event-seq instance)))
      (is (= 2.0 (get-in instance [:params :value]))))))
