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

(deftest tick-advances-per-instance-age-test
  (testing "the real gap found while wiring the VFX cutover: :age never advanced,
            so every ?age/?progress read in real content would have stayed frozen
            forever (arc_ring_session.edn's own :duration-ticks based ?progress lerp)"
    (let [s (store)
          _ (runtime/ensure! s [:k] {:effect-id :arc-strike :seed 1
                                     :user {:start [0.0 0.0 0.0] :end [4.0 0.0 0.0]}})]
      (is (= 0 (:age (runtime/lookup s [:k]))))
      (runtime/tick! s 0.1)
      (is (= 1 (:age (runtime/lookup s [:k]))))
      (runtime/tick! s 0.1)
      (is (= 2 (:age (runtime/lookup s [:k])))))))

(def ^:private scene-registry
  {:with-scene
   {:scene "{:ability :probe :do [(finish {:outcome :performed})]}"
    :user-types {:duration-ticks :int}
    :emitters []
    :lifecycle :transient}
   :with-scene-2
   {:scene "{:ability :probe2 :do [(finish {:outcome :performed})]}"
    :emitters []
    :lifecycle :transient}
   :emitter-only
   {:emitters []
    :lifecycle :transient}})

(deftest scene-program-is-compiled-once-per-effect-id-and-cached-test
  (testing "A2: compile-instance used to recompile the scene program on
            EVERY spawn (0.2-1.8ms and up to ~1MB on the worst shipped
            effects), even though the program never depends on the
            spawning instance -- see compiled-scene-program's docstring"
    (let [s (runtime/create-store scene-registry)
          a (runtime/ensure! s [:a] {:effect-id :with-scene :seed 1 :user {:duration-ticks 4}})
          b (runtime/ensure! s [:b] {:effect-id :with-scene :seed 2 :user {:duration-ticks 4}})]
      (is (identical? (:scene-program a) (:scene-program b))
          "two instances of the SAME effect-id must share one compiled program object"))))

(deftest scene-program-cache-is-keyed-by-effect-id-test
  (let [s (runtime/create-store scene-registry)
        a (runtime/ensure! s [:a] {:effect-id :with-scene :seed 1 :user {:duration-ticks 4}})
        c (runtime/ensure! s [:c] {:effect-id :with-scene-2 :seed 1})]
    (is (not (identical? (:scene-program a) (:scene-program c)))
        "different effect-ids must not share a cached program")))

(deftest scene-program-cache-handles-emitter-only-decls-without-recompiling-test
  (testing "an effect decl with no :scene/:document compiles to a nil
            program -- contains? (not a nil-as-miss check) must still
            treat that nil as cached, not recompute it on every spawn"
    (let [s (runtime/create-store scene-registry)]
      (runtime/ensure! s [:a] {:effect-id :emitter-only :seed 1})
      (is (contains? @(:scene-programs s) :emitter-only))
      (is (nil? (get @(:scene-programs s) :emitter-only))))))

(deftest reload-resources-invalidates-the-scene-program-cache-test
  (let [rt (runtime/create-client-runtime scene-registry)
        before (runtime/ensure! rt [:a] {:effect-id :with-scene :seed 1 :user {:duration-ticks 4}})
        _ (runtime/destroy! rt [:a])
        _ (runtime/reload-resources! rt 2)
        after (runtime/ensure! rt [:a] {:effect-id :with-scene :seed 1 :user {:duration-ticks 4}})]
    (is (not (identical? (:scene-program before) (:scene-program after)))
        "reload-resources! must force a fresh compile, not keep serving the old cached program")))

(deftest independent-stores-do-not-share-a-scene-program-cache-test
  (testing "cn.li.ability.client-vfx-v2's preview runtime is built from a
            fresh create-runtime call on the SAME registry as production
            (see that ns's preview-runtime docstring) -- its cache must not
            be visible to, or corrupted by, production's cache"
    (let [rt-a (runtime/create-client-runtime scene-registry)
          rt-b (runtime/create-client-runtime scene-registry)
          a (runtime/ensure! rt-a [:a] {:effect-id :with-scene :seed 1 :user {:duration-ticks 4}})
          b (runtime/ensure! rt-b [:a] {:effect-id :with-scene :seed 1 :user {:duration-ticks 4}})]
      (is (not (identical? (:scene-programs rt-a) (:scene-programs rt-b))))
      (is (not (identical? (:scene-program a) (:scene-program b)))))))

(def ^:private transient-lifecycle-registry
  {:one-shot
   {:scene "{:ability :probe :do [(finish {:outcome :performed})]}"
    :emitters []
    :lifecycle :transient}
   :life-timed
   {:scene "{:ability :probe :do [(finish {:outcome :performed})]}"
    :emitters []
    :lifecycle :transient}})

(deftest client-runtime-dispatch-signal-dedup-and-tombstone-test
  (let [rt (runtime/create-client-runtime scene-registry)]
    (testing "spawn creates, a stale spawn (lower event-seq, no tombstone win) is ignored"
      (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                    :instance-key [:a] :event-seq 5 :params {:duration-ticks 4}})
      (is (some? (runtime/lookup rt [:a]))))
    (testing "destroy removes and remembers a tombstone"
      (runtime/dispatch-signal! rt {:op :destroy :effect-id :with-scene :owner "p1"
                                    :instance-key [:a] :event-seq 6})
      (is (nil? (runtime/lookup rt [:a]))))
    (testing "a delayed lower-sequence spawn cannot resurrect a destroyed key"
      (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                    :instance-key [:a] :event-seq 4 :params {:duration-ticks 4}})
      (is (nil? (runtime/lookup rt [:a]))))
    (testing "destroy-before-spawn also records the tombstone"
      (runtime/dispatch-signal! rt {:op :destroy :effect-id :with-scene :owner "p1"
                                    :instance-key [:missing] :event-seq 8})
      (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                    :instance-key [:missing] :event-seq 7
                                    :params {:duration-ticks 4}})
      (is (nil? (runtime/lookup rt [:missing]))))
    (testing "a spawn with a HIGHER event-seq than the tombstone succeeds"
      (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                    :instance-key [:a] :event-seq 7 :params {:duration-ticks 4}})
      (is (some? (runtime/lookup rt [:a]))))))

(deftest client-runtime-update-merges-params-and-ignores-stale-test
  (let [rt (runtime/create-client-runtime scene-registry)]
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                  :instance-key [:b] :event-seq 10
                                  :params {:duration-ticks 4 :value 1.0}})
    (runtime/dispatch-signal! rt {:op :update :effect-id :with-scene :owner "p1"
                                  :instance-key [:b] :event-seq 11 :params {:value 2.0}})
    (runtime/dispatch-signal! rt {:op :update :effect-id :with-scene :owner "p1"
                                  :instance-key [:b] :event-seq 9 :params {:value 99.0}})
    (let [instance (runtime/lookup rt [:b])]
      (is (= 11 (:event-seq instance)))
      (is (= 2.0 (get-in instance [:user :value]))
          "the stale event-seq 9 update must never overwrite the value from event-seq 11"))))

(deftest client-runtime-clear-owner-through-dispatch-signal-test
  (let [rt (runtime/create-client-runtime scene-registry)]
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                  :instance-key [:c] :event-seq 1 :params {:duration-ticks 4}})
    (runtime/dispatch-signal! rt {:op :clear-owner :owner "p1" :event-seq 0})
    (is (nil? (runtime/lookup rt [:c])))))

(deftest client-tick-auto-destroys-expired-transient-instances-test
  (let [rt (runtime/create-client-runtime scene-registry)]
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                  :instance-key [:d] :event-seq 1 :params {:duration-ticks 2}})
    (is (some? (runtime/lookup rt [:d])))
    (runtime/client-tick! rt 0.05)
    (is (some? (runtime/lookup rt [:d])) "age 1 < duration 2, still alive")
    (runtime/client-tick! rt 0.05)
    (is (nil? (runtime/lookup rt [:d])) "age 2 >= duration 2, auto-destroyed")))

(deftest client-tick-retires-one-shot-and-life-timed-transients-test
  (let [rt (runtime/create-client-runtime transient-lifecycle-registry)]
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :one-shot
                                  :instance-key [:one-shot] :event-seq 1})
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :life-timed
                                  :instance-key [:life-timed] :event-seq 1
                                  :params {:life-ticks 2}})
    (runtime/client-tick! rt 0.05)
    (is (nil? (runtime/lookup rt [:one-shot]))
        "a transient without a duration is a one-shot and retires after one tick")
    (is (some? (runtime/lookup rt [:life-timed]))
        "life-ticks keeps a transient alive for its visual lifetime")
    (runtime/client-tick! rt 0.05)
    (is (nil? (runtime/lookup rt [:life-timed]))
        "life-timed transient retires once its declared life is reached")
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :one-shot
                                  :instance-key [:one-shot] :event-seq 1})
    (is (nil? (runtime/lookup rt [:one-shot]))
        "a duplicate spawn cannot resurrect an auto-expired one-shot")
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :one-shot
                                  :instance-key [:one-shot] :event-seq 2})
    (is (some? (runtime/lookup rt [:one-shot]))
        "a new activation sequence can reuse the expired instance key")))

(deftest sample-client-frame-pools-by-frame-id-test
  (let [rt (runtime/create-client-runtime scene-registry {:max-frames 2})]
    (runtime/dispatch-signal! rt {:op :spawn :effect-id :with-scene :owner "p1"
                                  :instance-key [:e] :event-seq 1 :params {:duration-ticks 4}})
    (let [f1 (runtime/sample-client-frame! rt)
          f2 (runtime/sample-client-frame! rt)]
      (is (not= (:frame-id f1) (:frame-id f2)))
      (is (some? (:java-frame f1)))
      (is (some? (get @(:frames rt) (:frame-id f2))) "still pooled before release")
      (runtime/release-frame! rt (:frame-id f2))
      (is (nil? (get @(:frames rt) (:frame-id f2)))))))
