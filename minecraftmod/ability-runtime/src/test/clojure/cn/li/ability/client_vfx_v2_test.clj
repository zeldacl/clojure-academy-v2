(ns cn.li.ability.client-vfx-v2-test
  "Headless tests for the NEW-engine VFX composition root (parallel to
   client_vfx_test.clj, which exercises the old engine's own client-vfx
   directly and must keep passing unmodified)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.client-vfx-v2 :as controller]))

(defn- registry []
  {:probe-transient
   {:scene nil :user-types {:duration-ticks :int} :emitters [] :lifecycle :transient}
   :screen-flash-session
   {:scene nil :user-types {:alpha :float :duration-ticks :int} :emitters [] :lifecycle :transient}})

(defn- reset-runtime! []
  (controller/reset-for-test!)
  (controller/install-production! {:catalog-compile registry}))

(deftest stable-key-creates-independent-instances-test
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

(deftest stale-update-does-not-overwrite-parameters-test
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
      (is (= 2.0 (get-in instance [:user :value]))))))

(deftest tick-advances-age-and-auto-destroys-transient-test
  (reset-runtime!)
  (controller/dispatch-signal!
   {:op :spawn :effect-id :probe-transient :owner "owner-1"
    :world-id "world" :instance-key [:cast 4] :event-seq 1
    :params {:duration-ticks 2}})
  (is (= 1 (count @(:instances (controller/runtime)))))
  (controller/tick! {:delta-seconds 0.05})
  (is (= 1 (count @(:instances (controller/runtime)))) "age 1 < duration 2")
  (controller/tick! {:delta-seconds 0.05})
  (is (= 0 (count @(:instances (controller/runtime)))) "age 2 >= duration 2, auto-destroyed"))

(deftest screen-flash-side-channel-tracks-spawn-and-decays-test
  (reset-runtime!)
  (controller/dispatch-signal!
   {:op :spawn :effect-id :screen-flash-session :owner "owner-1" :world-id "world"
    :instance-key [:flash] :event-seq 1 :params {:alpha 0.5 :duration-ticks 2}})
  (is (= 0.5 (controller/screen-flash-alpha "owner-1")))
  (controller/dispatch-signal!
   {:op :destroy :effect-id :screen-flash-session :owner "owner-1" :world-id "world"
    :instance-key [:flash] :event-seq 2})
  (is (= 0.0 (controller/screen-flash-alpha "owner-1"))))

(deftest vfx-host-api-is-contract-valid-test
  (reset-runtime!)
  (is (map? (controller/vfx-host-api))))

(deftest sample-java-frame-returns-a-real-vfx-frame-test
  (reset-runtime!)
  (controller/dispatch-signal!
   {:op :spawn :effect-id :probe-transient :owner "owner-1" :world-id "world"
    :instance-key [:cast 5] :event-seq 1 :params {:duration-ticks 4}})
  (let [frame (controller/sample-java-frame! {:frame-id 1 :partial-tick 0.0})]
    (is (instance? cn.li.mcmod.runtime.vfx.VfxFrame frame))))

(deftest editor-preview-isolated-from-production-runtime-test
  (reset-runtime!)
  (is (nil? (controller/sample-preview-frame!)))
  (is (= [:editor-preview :probe-transient]
         (controller/start-preview! :probe-transient {:duration-ticks 2})))
  (is (empty? @(:instances (controller/runtime))))
  (is (instance? cn.li.mcmod.runtime.vfx.VfxFrame
                 (controller/sample-preview-frame!)))
  (controller/stop-preview!)
  (is (nil? (controller/sample-preview-frame!))))