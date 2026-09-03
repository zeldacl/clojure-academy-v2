(ns cn.li.ac.ability.service.combat-runtime-edn-activation-smoke-test
  "End-to-end smoke coverage: dispatch real activations for every migrated
   EDN ability and assert the VM does not throw.

   This is the class of test the codebase was missing before this suite --
   `edn-catalog_test` only ever checked that catalogs *load*, never that a
   program actually *executes* through its :expr nodes. That gap hid a
   real AbstractMethodError crash in every math/lerp-based growth curve
   (see combat-core/vm.clj and mcmod/expr.clj); this file exists so the
   next latent crash of that class is caught here, not in game."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ability.engine :as final-runtime]
            [cn.li.ability.session :as combat-sessions]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

;; final-runtime/production-runtime is a JVM-lifetime singleton lazily
;; installed by combat-runtime's dispatch-intent! on the first-ever dispatch
;; across the whole ac test suite, and never rebuilt afterward -- so whichever
;; ac test namespace happens to dispatch first permanently freezes the host's
;; :queries/:actions snapshot for every namespace after it. Combat Core's own
;; world-facing capabilities (:raycast, :entity/select, ...) are registered by
;; cn.li.combat.platform/install!, which only production bootstrap calls; no
;; ac unit test does, so a real end-to-end dispatch through a session ability
;; (railgun/thunder-clap) or a raycast-using instant ability (arc-gen) throws
;; "missing host query capability" the moment it reaches one of those nodes.
;; Force a fresh install for the tests in *this* namespace by pinning the
;; queries they actually reach (per the abilities' EDN :target/* components)
;; to safe "found nothing" stubs and resetting the frozen runtime singleton so
;; combat-runtime's own lazy-install path re-captures a snapshot that
;; includes them, then hand the singleton back to whatever it held before so
;; other namespaces are unaffected.
(defn- stub-raycast-miss
  "Mirror combat-core platform.clj's basic-raycast miss shape (position at
   the max-range endpoint, everything else nil/false) instead of a bare nil
   -- EDN programs read :aim-hit's :position/:block-position/:water? fields
   unconditionally even when nothing was hit, exactly like a real total-miss
   raycast in production."
  [{:keys [origin direction distance]}]
  (let [as-point (fn [{:keys [x y z]}] [(double (or x 0.0)) (double (or y 0.0)) (double (or z 0.0))])
        [sx sy sz] (as-point origin)
        [dx dy dz] (as-point direction)
        distance (double (or distance 0.0))
        position {:x (+ sx (* dx distance)) :y (+ sy (* dy distance)) :z (+ sz (* dz distance))}]
    {:hit-type :miss :hit? false :position position :block-position nil
     :water? false :attacked? false :entity-id nil :entity-type nil
     :target-id nil :target-width 0.5 :target-height 0.0 :drop-position position}))

(defn- with-stubbed-world-queries [f]
  (let [prior-queries (select-keys (:queries (capabilities/snapshot))
                                   [:raycast :entity/select :item/held])
        prior-runtime (final-runtime/production-runtime)]
    (try
      (capabilities/register-query! :raycast stub-raycast-miss {:allow-overwrite? true})
      (capabilities/register-query! :entity/select (fn [_request] []) {:allow-overwrite? true})
      (capabilities/register-query! :item/held
                                    (fn [_request]
                                      {:present? false :placeable? false
                                       :item-id nil :block-id nil :count 0 :source nil})
                                    {:allow-overwrite? true})
      (reset! @#'cn.li.ability.engine/production-runtime* nil)
      (f)
      (finally
        (doseq [[capability handler] prior-queries]
          (capabilities/register-query! capability handler {:allow-overwrite? true}))
        (reset! @#'cn.li.ability.engine/production-runtime* prior-runtime)))))

(use-fixtures :once with-stubbed-world-queries)

(use-fixtures :each
  (fn [f]
    (combat-catalog/initialize!)
    (player-state-support/clean-player-states-fixture
     (fn []
       (runtime-store/create-session! player-state-support/test-session-id)
       (combat-sessions/reset-for-test!)
       (try
         (f)
         (finally
           (combat-sessions/reset-for-test!)))))))

(defn- dispatch! [owner ability-id op]
  (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
  ;; cmd-consume-resource's can-perform? gate requires :activated true (an
  ;; ordinary player's resource pool won't spend CP/overload while
  ;; deactivated) -- a fresh player-state defaults to false, so any ability
  ;; step that actually spends a resource (thunder-clap's per-tick CP cost,
  ;; for one) would otherwise throw "final state resource commit rejected"
  ;; deep in commit-final-state!, unrelated to whatever the test is checking.
  (runtime-store/set-player-state!
   player-state-support/test-session-id owner
   (assoc-in (runtime-store/get-player-state player-state-support/test-session-id owner)
             [:resource-data :activated] true))
  (combat-runtime/dispatch-intent! owner {:op op :ability-id ability-id}))

(deftest arc-gen-start-does-not-throw
  (testing "instant ability, :cost-cp/:cost-overload/:exp-entity/:exp-block/:cooldown-ticks lerps all evaluate"
    (let [result (dispatch! "smoke-arc-gen" :arc-gen :start)]
      (is (= :accepted (:status result))))))

(deftest railgun-start-then-pulse-then-release-do-not-throw
  (testing "session ability across :start -> :pulse -> :release"
    (is (= :accepted (:status (dispatch! "smoke-railgun" :railgun :start))))
    (is (= :accepted (:status (dispatch! "smoke-railgun" :railgun :pulse))))
    (is (= :accepted (:status (dispatch! "smoke-railgun" :railgun :release))))))

(deftest thunder-clap-start-then-pulse-do-not-throw
  (testing "session ability: :start (overload cost) then :pulse (conditional per-tick cp cost, charged-area-damage lerps)"
    (is (= :accepted (:status (dispatch! "smoke-thunder-clap" :thunder-clap :start))))
    (is (= :accepted (:status (dispatch! "smoke-thunder-clap" :thunder-clap :pulse))))))

(deftest vec-reflection-start-then-pulse-do-not-throw
  (testing "toggle ability: :start (overload-keep invariant) then :pulse (tick-cp cost, projectile-reflection-scan)"
    (is (= :accepted (:status (dispatch! "smoke-vec-reflection" :vec-reflection :start))))
    (is (= :accepted (:status (dispatch! "smoke-vec-reflection" :vec-reflection :pulse))))))

(defn- flush-with-resources! [owner]
  (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
  (runtime-store/set-player-state!
   player-state-support/test-session-id owner
   ;; Final-engine's generic spend-budget (combat-core final_engine.clj) gates
   ;; EVERY named :resources entry uniformly on "current >= required", :overload
   ;; included -- even though the real domain semantics (ability/model/resource's
   ;; add-overload, invoked by the resulting :consume-resource command) treat
   ;; :cur-overload as a rising heat gauge with no availability gate of its own.
   ;; A fresh player-state starts at :cur-overload 0.0, which the generic gate
   ;; reads as "insufficient" for any positive overload cost. Seed :cur-overload
   ;; to the cap (not 1.0e9 -- that would exceed :max-overload and immediately
   ;; flip :overload-fine false via add-overload's hit-cap? check, which then
   ;; blocks every subsequent resource-consuming command via can-use-ability?).
   (let [state (runtime-store/get-player-state player-state-support/test-session-id owner)
         max-overload (get-in state [:resource-data :max-overload] 100.0)]
     (update state :resource-data merge
             {:cur-cp 1.0e9 :cur-overload max-overload
              :overload-fine true :activated true}))))

(deftest arc-gen-v2-rewrite-deducts-cost-gains-exp-and-sets-cooldown
  (testing "schema v2 arc-gen: :cost/spend deducts both cp and overload, :score/mark gains exp,
            :cooldown/start writes the main cooldown -- proving the v2 rewrite preserves the
            v1 program's behavior via the new declarative :costs/:progression/:cooldown blocks.

            final-engine's execute! result map has no :actions/:owner-patch projection of the
            committed :txn (only :vfx-signals/:feedback/:events survive to the caller) -- assert
            against the real committed player-state instead, the same way
            combat_runtime_vanilla_damage_reflection_test.clj does for its own real-dispatch
            assertions.

            :score/mark only emits a :score/mark :events entry from inside the graph;
            dispatch-intent! never reads :events itself -- dispatch-result-domain-events!
            is a deliberately separate seam production request handlers call afterward
            (\"the caller controls when this seam is invoked\", per its own docstring), so
            a unit test dispatching directly must call it too or the exp gain never lands."
    (flush-with-resources! "smoke-arcgen-behavior")
    (let [result (combat-runtime/dispatch-intent!
                  "smoke-arcgen-behavior" {:op :start :ability-id :arc-gen})
          _ (combat-runtime/dispatch-result-domain-events! "smoke-arcgen-behavior" result)
          committed (runtime-store/get-player-state
                     player-state-support/test-session-id "smoke-arcgen-behavior")]
      (is (= :accepted (:status result)))
      (is (= :performed (:outcome result)))
      (is (< (get-in committed [:resource-data :cur-cp]) 1.0e9)
          "activate cost must deduct cp")
      (is (pos? (get-in committed [:resource-data :until-overload-recover]))
          "activate cost must add overload (add-overload always sets the recovery cooldown)")
      (is (pos? (get-in committed [:ability-data :skill-exps :arc-gen] 0.0))
          "hitting a block/entity must gain exp via :score/mark")
      (is (pos? (get-in committed [:cooldown-data [:arc-gen :main] :ticks] 0))
          ":cooldown/start must write the main cooldown"))))

(deftest actions-and-vfx-actually-propagate-out-of-a-real-dispatch
  (testing "the VM's frame-collected vfx/events survive :flow/finish and reach dispatch-intent!'s
            caller, and the frame's txn commits actually land in the real player-state store
            (regression for the :flow/finish result map that used to silently drop them all --
            every EDN ability's cost deduction, exp gain, cooldown write, and VFX spawn was a
            no-op in production)"
    (flush-with-resources! "smoke-actions-arc-gen")
    (let [arc-gen-result (combat-runtime/dispatch-intent!
                          "smoke-actions-arc-gen" {:op :start :ability-id :arc-gen})
          committed (runtime-store/get-player-state
                     player-state-support/test-session-id "smoke-actions-arc-gen")]
      (is (= :accepted (:status arc-gen-result)))
      (is (< (get-in committed [:resource-data :cur-cp]) 1.0e9)
          "arc-gen's unconditional cp cost must actually commit to the player-state store"))
    (flush-with-resources! "smoke-actions-railgun")
    (let [railgun-result (combat-runtime/dispatch-intent!
                          "smoke-actions-railgun" {:op :start :ability-id :railgun})]
      (is (= :accepted (:status railgun-result)))
      (is (seq (:vfx-signals railgun-result))
          "railgun's :start spawns the railgun-charge VFX; it must appear in :vfx-signals"))))

