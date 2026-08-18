(ns cn.li.ac.client.effect-controller-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.client.effect-controller :as vfx-level]))

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

;; Batch 5 (mine_detect): a :level build-plan-fn sometimes needs to write
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
