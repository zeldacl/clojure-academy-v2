(ns cn.li.vfx.install-test
  "End-to-end coverage: a compiled EDN effect document actually drives
   vfx-core's real lifecycle (spawn/tick/sample/destroy) and produces
   batches -- the class of test that would have caught install-catalog!'s
   entire reason for existing (EDN effects compiled fine but nothing ever
   turned them into a registered, spawnable vfx-core effect)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.components :as components]
            [cn.li.vfx.install :as install]
            [cn.li.vfx.recipe :as recipe]
            [cn.li.vfx.runtime :as runtime]))

(defn- ring-session-effect []
  {:schema-version 1 :kind :vfx-effect :id :test/ring-session
   :revision 1 :lifecycle :session
   :inputs {:spawn {:center :vec3 :radius :double}
            :update {:center :vec3}}
   :state-slots {:age :double :seed :long}
   :graph {:component :vfx/ring
           :center {:ref [:input :center]}
           :radius {:ref [:input :radius]}
           :segments 12
           :color [255 255 255 255]}
   :bounds {:component :vfx/beam-bounds
            :start {:ref [:input :center]} :end {:ref [:input :center]}
            :radius {:ref [:input :radius]}}})

(defn- beam-transient-effect []
  {:schema-version 1 :kind :vfx-effect :id :test/beam-transient
   :revision 1 :lifecycle :transient
   :inputs {:spawn {:start :vec3 :end :vec3}}
   :state-slots {:age :double :seed :long}
   :graph {:component :vfx/timeline
           :duration-ticks 2
           :children [{:at 0
                       :node {:component :vfx/beam
                              :start {:ref [:input :start]}
                              :end {:ref [:input :end]}
                              :layers []}}]}})

(defn- catalog-of [& effects]
  (components/reset-for-test!)
  {:effects (into {} (map (fn [e] [(:id e) (recipe/compile-effect e)])) effects)})

(deftest install-catalog-registers-every-compiled-effect
  (let [rt (runtime/create-runtime)
        catalog (catalog-of (ring-session-effect) (beam-transient-effect))]
    (install/install-catalog! rt catalog)
    (is (= #{:test/ring-session :test/beam-transient}
           (runtime/registered-effects rt)))))

(deftest install-catalog-is-idempotent-for-already-registered-ids
  (let [rt (runtime/create-runtime)
        catalog (catalog-of (ring-session-effect))]
    (install/install-catalog! rt catalog)
    (is (= #{:test/ring-session} (install/install-catalog! rt catalog)))))

(deftest a-session-effect-spawns-ticks-samples-and-only-ends-on-explicit-destroy
  (let [rt (runtime/create-runtime)]
    (install/install-catalog! rt (catalog-of (ring-session-effect)))
    (runtime/freeze-registry! rt)
    (is (= :session (runtime/effect-lifecycle rt :test/ring-session)))
    (runtime/dispatch-signal!
     rt {:op :spawn :effect-id :test/ring-session :instance-key [:t 1]
         :owner :owner :event-seq 1 :event :spawn
         :params {:center {:x 1.0 :y 2.0 :z 3.0} :radius 4.0}})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (let [frame (runtime/sample-frame! rt {:frame-id 1 :partial-tick 0.0})
          batches (get-in frame [:stages :world-after-translucent])]
      (is (= 1 (count batches)))
      (is (= 4.0 (:radius-from (first (:payload (first batches)))))))
    ;; A :session effect never self-expires no matter how many ticks pass.
    (dotimes [n 500] (runtime/tick! rt {:tick-id (+ 2 n) :delta-seconds 1.0}))
    (is (some? (runtime/instance-for-key rt [:t 1])))
    (runtime/dispatch-signal!
     rt {:op :destroy :effect-id :test/ring-session :instance-key [:t 1]
         :owner :owner :event-seq 2})
    (is (nil? (runtime/instance-for-key rt [:t 1])))))

(deftest a-transient-effect-self-destroys-after-its-graphs-own-duration
  (let [rt (runtime/create-runtime)]
    (install/install-catalog! rt (catalog-of (beam-transient-effect)))
    (runtime/freeze-registry! rt)
    (is (= :transient (runtime/effect-lifecycle rt :test/beam-transient)))
    (runtime/dispatch-signal!
     rt {:op :spawn :effect-id :test/beam-transient :instance-key [:t 2]
         :owner :owner :event-seq 1 :event :spawn
         :params {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 10.0 :y 0.0 :z 0.0}}})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (is (some? (runtime/instance-for-key rt [:t 2])) "age 1 of 2 ticks: still alive")
    (runtime/tick! rt {:tick-id 2 :delta-seconds 0.05})
    (is (nil? (runtime/instance-for-key rt [:t 2])) "age 2 of 2 ticks: self-destroyed")))

;; ---------------------------------------------------------------------------
;; validate-requirements!
;; ---------------------------------------------------------------------------

(defn- requirement [effect-id operation payload-keys]
  {:ability-id :test-ability :effect-id effect-id :operation operation
   :payload-keys payload-keys})

(deftest missing-required-field-is-flagged
  (let [catalog {:effects {:e {:inputs {:spawn {:center :vec3 :radius :double}}}}}
        failures (install/validate-requirements!
                  catalog [(requirement :e :spawn #{:center})])]
    (is (= 1 (count failures)))
    (is (= :missing-required-fields (:reason (first failures))))
    (is (= #{:radius} (:missing-fields (first failures))))))

(deftest satisfied-requirement-passes
  (let [catalog {:effects {:e {:inputs {:spawn {:center :vec3 :radius :double}}}}}]
    (is (empty? (install/validate-requirements!
                 catalog [(requirement :e :spawn #{:center :radius})])))))

(deftest unknown-effect-id-is-flagged
  (let [failures (install/validate-requirements!
                  {:effects {}} [(requirement :nope :spawn #{})])]
    (is (= :unknown-vfx-effect (:reason (first failures))))))

(deftest extra-payload-fields-are-not-flagged
  (testing "an unused field just sits unread in :input -- harmless, not a bug"
    (let [catalog {:effects {:e {:inputs {:spawn {:center :vec3}}}}}]
      (is (empty? (install/validate-requirements!
                   catalog [(requirement :e :spawn #{:center :unused-extra})]))))))

(deftest a-field-with-a-default-is-optional-not-required
  (let [catalog {:effects {:e {:inputs {:spawn {:alpha {:type :double :default 1.0}}}}}}]
    (is (empty? (install/validate-requirements!
                 catalog [(requirement :e :spawn #{})])))))

(deftest an-operation-the-effect-declares-no-inputs-for-is-not-flagged
  (testing "e.g. a :destroy with no :inputs :destroy entry -- see mag_manip.edn's
            :fade-ticks against effects that never declared a :destroy schema"
    (let [catalog {:effects {:e {:inputs {:spawn {:center :vec3}}}}}]
      (is (empty? (install/validate-requirements!
                   catalog [(requirement :e :destroy #{:fade-ticks})]))))))

(deftest an-update-signal-changes-what-the-next-sample-emits
  (let [rt (runtime/create-runtime)]
    (install/install-catalog! rt (catalog-of (ring-session-effect)))
    (runtime/freeze-registry! rt)
    (runtime/dispatch-signal!
     rt {:op :spawn :effect-id :test/ring-session :instance-key [:t 3]
         :owner :owner :event-seq 1 :event :spawn
         :params {:center {:x 0.0 :y 0.0 :z 0.0} :radius 1.0}})
    (runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
    (runtime/dispatch-signal!
     rt {:op :signal :effect-id :test/ring-session :instance-key [:t 3]
         :owner :owner :event-seq 2 :event :update
         :params {:center {:x 9.0 :y 0.0 :z 0.0}}})
    (runtime/tick! rt {:tick-id 2 :delta-seconds 0.05})
    (let [frame (runtime/sample-frame! rt {:frame-id 2 :partial-tick 0.0})
          batch (first (get-in frame [:stages :world-after-translucent]))]
      (is (= {:x 9.0 :y 0.0 :z 0.0} (:center (first (:payload batch)))))
      ;; :radius was only ever supplied at :spawn -- an :update payload
      ;; merges into the same :input map, it doesn't replace it.
      (is (= 1.0 (:radius-from (first (:payload batch))))))))
