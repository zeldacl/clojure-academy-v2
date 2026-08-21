(ns cn.li.ac.client.effect-controller-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.vfx.install :as vfx-install]))

;; See effect_controller.clj's descriptor :update docstring: apply-tick only
;; ever dissoc's the inner :level/:hand key when a per-track tick-state-fn
;; returns nil -- it never makes the whole {:level .. :hand ..} map itself
;; nil, which is the only thing vfx-core's tick! treats as "this instance
;; naturally ended" (see vfx-core/runtime.clj's tick-instance). Without the
;; :transient-lifecycle check in descriptor, a :transient instance whose
;; last live track just went nil would sit forever as an empty {} state,
;; still ticked/sampled every frame, until the owner disconnects -- this
;; test is the regression guard for that leak.
(deftest transient-instance-naturally-ends-when-tick-state-fn-returns-nil-test
  (vfx-level/reset-for-test!)
  (vfx-level/register-effect!
    :natural-end-probe
    {:lifecycle :transient
     :level {:initial-state (fn [] {:active? true})
             :enqueue-state-fn (fn [state _ _ _ _] state)
             :tick-state-fn (fn [state] (when (:active? state) (assoc state :active? false)))
             :build-plan-fn (fn [_ _ _ _] nil)}})
  (vfx-level/dispatch-signal!
    {:op :spawn :effect-id :natural-end-probe :owner "p1" :world-id "w"
     :instance-key [:probe "p1"] :event-seq 0 :event :start :params {}})
  (vfx-level/tick! {:tick-id 1 :delta-seconds 0.05})
  (is (= 1 (count @(:instances (vfx-level/runtime))))
      "the instance exists after spawn and its first tick (still :active? true -> false)")
  (vfx-level/tick! {:tick-id 2 :delta-seconds 0.05})
  (is (= 0 (count @(:instances (vfx-level/runtime))))
      "the vfx-core instance itself must be torn down once its last live track's tick-state-fn goes nil"))

(deftest singleton-instance-never-naturally-ends-test
  (vfx-level/reset-for-test!)
  (vfx-level/register-effect!
    :singleton-probe
    {:lifecycle :singleton
     :level {:initial-state (fn [] {:active? true})
             :enqueue-state-fn (fn [state _ _ _ _] state)
             :tick-state-fn (fn [state] (when (:active? state) (assoc state :active? false)))
             :build-plan-fn (fn [_ _ _ _] nil)}})
  (vfx-level/tick! {:tick-id 1 :delta-seconds 0.05})
  (vfx-level/tick! {:tick-id 2 :delta-seconds 0.05})
  (is (= 1 (count @(:instances (vfx-level/runtime))))
      "the one eternal aggregate instance must survive its tracked state going empty"))

(deftest generic-camera-fov-session-is-owner-scoped-and-eases-on-destroy-test
  (vfx-level/reset-for-test!)
  (vfx-level/dispatch-signal!
    {:op :spawn :effect-id :camera-fov-session :owner "p1"
     :instance-key [:camera "p1"] :event-seq 1 :params {:offset 24.0}})
  (is (= 2.88 (double (vfx-level/current-fov-offset "p1")))
      "the generic camera signal is consumed without a skill-specific handler")
  (is (= 0.0 (double (vfx-level/current-fov-offset "p2"))))
  (vfx-level/dispatch-signal!
    {:op :destroy :effect-id :camera-fov-session :owner "p1"
     :instance-key [:camera "p1"] :event-seq 2 :params {}})
  (is (< (double (vfx-level/current-fov-offset "p1")) 2.88)
      "destroy clears the target while retaining a short easing tail"))

;; Batch 4 (directed_shock/mag_manip): a :hand transform-fn has no per-call
;; context of its own to carry an owner through (sample-hand! calls it with
;; zero arguments), so a :hand-only :transient effect needs a way to find
;; "my own" instance given an owner resolved from elsewhere (e.g. the local
;; player's uuid). instance-for-owner is that lookup.
(deftest instance-for-owner-finds-only-the-matching-owners-hand-state-test
  (vfx-level/reset-for-test!)
  (vfx-level/register-effect!
    :owner-probe
    {:lifecycle :transient
     :hand {:initial-state (fn [] {})
            :enqueue-state-fn (fn [state _ _ _ payload] (merge state payload))
            :tick-state-fn (fn [state] state)
            :transform-fn (fn [] nil)}})
  (vfx-level/dispatch-signal!
    {:op :spawn :effect-id :owner-probe :owner "mine" :world-id "w"
     :instance-key [:probe "mine"] :event-seq 0 :event :tag :params {:whose "mine"}})
  (vfx-level/dispatch-signal!
    {:op :spawn :effect-id :owner-probe :owner "someone-else" :world-id "w"
     :instance-key [:probe "someone-else"] :event-seq 0 :event :tag :params {:whose "someone-else"}})
  (vfx-level/tick! {:tick-id 1 :delta-seconds 0.05})
  (is (= {:whose "mine" :mode :tag} (vfx-level/instance-for-owner :owner-probe "mine")))
  (is (nil? (vfx-level/instance-for-owner :owner-probe "nobody"))))

;; A level build-plan-fn sometimes needs to write
;; back into ITS OWN instance's state (a query-fn result only available at
;; sample time, never passed to tick-state-fn) without disturbing any other
;; concurrent caster's instance of the same effect. update-state! alone
;; can't do this -- it targets core/instance-for-effect, "the first
;; instance of this effect-id", an arbitrary pick once more than one
;; instance exists.
(deftest update-state-for-owner-only-touches-the-matching-owners-instance-test
  (vfx-level/reset-for-test!)
  (vfx-level/register-effect!
    :owner-write-probe
    {:lifecycle :transient
     :level {:initial-state (fn [] {:count 0})
             :enqueue-state-fn (fn [state _ _ _ _] state)
             :tick-state-fn (fn [state] state)
             :build-plan-fn (fn [_ _ _ _] nil)}})
  (vfx-level/dispatch-signal!
    {:op :spawn :effect-id :owner-write-probe :owner "mine" :world-id "w"
     :instance-key [:probe "mine"] :event-seq 0 :event :start :params {}})
  (vfx-level/dispatch-signal!
    {:op :spawn :effect-id :owner-write-probe :owner "someone-else" :world-id "w"
     :instance-key [:probe "someone-else"] :event-seq 0 :event :start :params {}})
  (vfx-level/tick! {:tick-id 1 :delta-seconds 0.05})
  (vfx-level/update-state-for-owner! :owner-write-probe "mine" :level update :count inc)
  (is (= {:count 1} (vfx-level/instance-for-owner :owner-write-probe "mine" :level)))
  (is (= {:count 0} (vfx-level/instance-for-owner :owner-write-probe "someone-else" :level))
      "a different owner's instance of the same effect must be untouched"))

;; Regression test for the actual bug this session's Phase B closed: before
;; install-catalog! existed, a real ability's :effect/vfx signal (e.g.
;; railgun's :beam-arc-fade) compiled fine but had no registered effect-id
;; to dispatch to, so dispatch-signal! always fell through to the
;; "unregistered effect-id" branch and incremented unmapped-signal-count*
;; forever, silently. This drives the exact combat-vfx-adapter/
;; skill-runtime signal shape (:transient lifecycle, a real production
;; effect-id) through the real client dispatch path -- not just vfx-core's
;; own runtime primitives (see vfx-core/install_test.clj for that layer).
(deftest a-real-edn-vfx-effect-dispatches-through-the-client-path-not-as-unmapped
  (vfx-level/reset-for-test!)
  (combat-catalog/initialize!)
  (vfx-install/install-catalog! (vfx-level/runtime) (:vfx (combat-catalog/catalog)))
  (let [before (vfx-level/unmapped-signal-count)]
    (vfx-level/dispatch-signal!
     {:op :spawn :effect-id :beam-arc-fade :owner "p1" :world-id "w"
      :instance-key [:activation "p1" :beam-arc-fade] :event-seq 1 :event :spawn
      :params {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 10.0 :y 0.0 :z 0.0}
               :layers []}})
    (is (= before (vfx-level/unmapped-signal-count))
        "a registered production effect-id must never fall into the unmapped-signal path")
    (is (= 1 (count @(:instances (vfx-level/runtime)))))))
