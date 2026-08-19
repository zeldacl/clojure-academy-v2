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
            [cn.li.ac.ability.service.edn-catalog :as edn-catalog]
            [cn.li.ac.ability.service.edn-sessions :as edn-sessions]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]))

(use-fixtures :each
  (fn [f]
    (edn-catalog/initialize!)
    (player-state-support/clean-player-states-fixture
     (fn []
       (runtime-store/create-session! player-state-support/test-session-id)
       (edn-sessions/reset-for-test!)
       (try
         (f)
         (finally
           (edn-sessions/reset-for-test!)))))))

(defn- dispatch! [owner ability-id op]
  (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
  (combat-runtime/dispatch-intent! owner {:action op :ability-id ability-id}))

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
   (update (runtime-store/get-player-state player-state-support/test-session-id owner)
           :resource-data merge {:cur-cp 1.0e9 :cur-overload 1.0e9})))

(deftest arc-gen-v2-rewrite-deducts-cost-gains-exp-and-sets-cooldown
  (testing "schema v2 arc-gen: :cost/spend deducts both cp and overload, :score/mark gains exp,
            :cooldown/start writes the main cooldown -- proving the v2 rewrite preserves the
            v1 program's behavior via the new declarative :costs/:progression/:cooldown blocks"
    (flush-with-resources! "smoke-arcgen-behavior")
    (let [result (combat-runtime/dispatch-intent!
                  "smoke-arcgen-behavior" {:action :start :ability-id :arc-gen})
          entries (mapcat :entries (filter #(= :owner-patch (:type %)) (:actions result)))]
      (is (= :accepted (:status result)))
      (is (= :performed (:outcome result)))
      (is (some #(= [:resources :cp] (:path %)) entries)
          "activate cost must deduct cp")
      (is (some #(= [:resources :overload] (:path %)) entries)
          "activate cost must deduct overload")
      (is (some #(= [:ability-data :skill-exps :arc-gen] (:path %)) entries)
          "hitting a block/entity must gain exp via :score/mark")
      (is (some #(= [:cooldown-data :arc-gen :main] (:path %)) entries)
          ":cooldown/start must write the main cooldown"))))

(deftest actions-and-vfx-actually-propagate-out-of-a-real-dispatch
  (testing "the VM's frame-collected actions/vfx/events survive :flow/finish and reach dispatch-intent!'s caller
            (regression for the :flow/finish result map that used to silently drop them all -- every EDN
            ability's cost deduction, exp gain, cooldown write, and VFX spawn was a no-op in production)"
    (flush-with-resources! "smoke-actions-arc-gen")
    (let [arc-gen-result (combat-runtime/dispatch-intent!
                          "smoke-actions-arc-gen" {:action :start :ability-id :arc-gen})]
      (is (= :accepted (:status arc-gen-result)))
      (is (seq (:actions arc-gen-result))
          "arc-gen's unconditional cp/overload cost owner-patch must appear in :actions")
      (is (some #(= :owner-patch (:type %)) (:actions arc-gen-result))))
    (flush-with-resources! "smoke-actions-railgun")
    (let [railgun-result (combat-runtime/dispatch-intent!
                          "smoke-actions-railgun" {:action :start :ability-id :railgun})]
      (is (= :accepted (:status railgun-result)))
      (is (seq (:vfx-signals railgun-result))
          "railgun's :start spawns the railgun-charge VFX; it must appear in :vfx-signals"))))
