(ns cn.li.vfx.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.runtime :as runtime]))

(def ^:private sparks-registry
  {:arc-strike
   {:scene nil
    :emitters
    [{:id :sparks :capacity 16
      :attrs {:position :vec3 :velocity :vec3 :age :float :lifetime :float}
      :spawn [{:module :spawn/burst :count 4}
             {:module :location/line :from [:context :start] :to [:context :end]}
             {:module :velocity/const :value [0.0 0.05 0.0]}
             {:module :set :attr :lifetime :value 2.0}]
      :update [{:module :integrate} {:module :kill-expired}]}]}})

(defn- store [] (runtime/create-store sparks-registry))

(deftest ensure-creates-and-idempotently-returns-the-same-instance-test
  (let [s (store)
        spawn {:effect-id :arc-strike :seed 42 :owner :p1 :world-id "w1"
              :user {:start [0.0 0.0 0.0] :end [4.0 0.0 0.0]}}
        first-call (runtime/ensure! s [:activation 1] spawn)
        second-call (runtime/ensure! s [:activation 1] {:effect-id :arc-strike :seed 999})]
    (testing "instance created with the spawn's own seed"
      (is (= 42 (:seed first-call))))
    (testing "a second ensure! for the SAME key is a no-op -- the stale seed proves it
              did not recompile/replace, even though a different seed was passed"
      (is (= 42 (:seed second-call)))
      (is (identical? first-call second-call)))))

(deftest lookup-is-keyed-not-scanned-test
  (testing "distinct instance-keys never collide, and each keeps its own seed --
            the concrete property that matters is O(1) keyed lookup, not a linear
            scan across every live instance for one key"
    (let [s (store)]
      (runtime/ensure! s [:a 1] {:effect-id :arc-strike :seed 1 :user {:start [0.0 0.0 0.0] :end [1.0 0.0 0.0]}})
      (runtime/ensure! s [:a 2] {:effect-id :arc-strike :seed 2 :user {:start [0.0 0.0 0.0] :end [1.0 0.0 0.0]}})
      (is (= 1 (:seed (runtime/lookup s [:a 1]))))
      (is (= 2 (:seed (runtime/lookup s [:a 2]))))
      (is (nil? (runtime/lookup s [:a 3])))
      (is (= 2 (count (runtime/instances s)))))))

(deftest seed-is-not-always-zero-test
  (testing "the old final-client bug: every instance shared seed 0 regardless of
            what the spawn signal carried. Two instances of the SAME effect with
            DIFFERENT seeds must keep them distinct."
    (let [s (store)]
      (runtime/ensure! s [:x] {:effect-id :arc-strike :seed 7 :user {:start [0.0 0.0 0.0] :end [1.0 0.0 0.0]}})
      (runtime/ensure! s [:y] {:effect-id :arc-strike :seed 13 :user {:start [0.0 0.0 0.0] :end [1.0 0.0 0.0]}})
      (is (not= (:seed (runtime/lookup s [:x])) (:seed (runtime/lookup s [:y]))))
      (is (= 0 (:seed (runtime/ensure! s [:z] {:effect-id :arc-strike
                                               :user {:start [0.0 0.0 0.0] :end [1.0 0.0 0.0]}})))
          "seed defaults to 0 only when genuinely omitted"))))

(deftest spawn-runs-once-not-every-tick-test
  (let [s (store)
        _ (runtime/ensure! s [:burst] {:effect-id :arc-strike :seed 1
                                       :user {:start [0.0 0.0 0.0] :end [4.0 0.0 0.0]}})
        buffer-size #(-> (runtime/lookup s [:burst]) :emitters first :buffer .size)]
    (is (= 4 (buffer-size)) "spawn/burst reserved exactly 4 particles at ensure! time")
    (runtime/tick! s 0.1)
    (is (= 4 (buffer-size)) "tick! must not re-run spawn -- particle count stays 4, not 8")
    (runtime/tick! s 0.1)
    (is (= 4 (buffer-size)))))

(deftest tick-integrates-and-expires-test
  (let [s (store)
        _ (runtime/ensure! s [:k] {:effect-id :arc-strike :seed 1
                                   :user {:start [0.0 0.0 0.0] :end [4.0 0.0 0.0]}})
        buffer-size #(-> (runtime/lookup s [:k]) :emitters first :buffer .size)]
    (is (= 4 (buffer-size)))
    (runtime/tick! s 1.0)
    (is (= 4 (buffer-size)) "one tick: age 1.0 < lifetime 2.0, still alive")
    (runtime/tick! s 1.0)
    (is (= 0 (buffer-size)) "second tick: age 2.0 >= lifetime 2.0, kill-expired removed all 4")))

(deftest destroy-and-clear-test
  (let [s (store)
        spawn {:effect-id :arc-strike :seed 1 :owner :p1 :world-id "w1"
              :user {:start [0.0 0.0 0.0] :end [1.0 0.0 0.0]}}]
    (runtime/ensure! s [:a] (assoc spawn :owner :p1))
    (runtime/ensure! s [:b] (assoc spawn :owner :p2))
    (runtime/destroy! s [:a])
    (is (nil? (runtime/lookup s [:a])))
    (is (some? (runtime/lookup s [:b])))
    (runtime/ensure! s [:c] (assoc spawn :owner :p2))
    (runtime/clear-owner! s :p2)
    (is (nil? (runtime/lookup s [:b])))
    (is (nil? (runtime/lookup s [:c])))
    (runtime/ensure! s [:d] spawn)
    (runtime/clear-world! s nil)
    (is (empty? (runtime/instances s)))))

(deftest sample-frame-returns-emitter-layout-and-buffer-per-instance-test
  (let [s (store)
        _ (runtime/ensure! s [:k] {:effect-id :arc-strike :seed 1
                                   :user {:start [0.0 0.0 0.0] :end [4.0 0.0 0.0]}})
        frame (runtime/sample-frame! s)]
    (is (contains? frame [:k]))
    (is (nil? (get-in frame [[:k] :scene])) "this registry entry declares no :scene")
    (is (= 1 (count (get-in frame [[:k] :emitters]))))
    (is (= 4 (.size ^cn.li.mcmod.runtime.vfx.ParticleColumns (:buffer (first (get-in frame [[:k] :emitters]))))))))
