(ns cn.li.ac.ability.service.combat-runtime-edn-fixes-test
  "Regression coverage for the Phase 0 EDN v1 bug fixes: multi-entry
   owner-patch commit, guarded_owner_patch entries application, instant
   ability session leak, and per-activation RNG seed generation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.combat-sessions :as combat-sessions]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]))

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

(deftest owner-patch-commands-commit-every-entry
  (testing "one owner-patch action carrying both a cp and an overload entry commits both, not just the first (bug #1)"
    (let [commands (#'combat-runtime/edn-owner-patch-commands
                     [{:type :owner-patch
                       :entries [{:path [:resources :cp] :mode :increment :value -5.0}
                                 {:path [:resources :overload] :mode :increment :value -3.0}]}])]
      (is (= 2 (count commands)))
      (is (some #(= {:command :consume-resource :cp 5.0} %) commands))
      (is (some #(= {:command :consume-resource :overload 3.0} %) commands)))))

(deftest owner-patch-commands-commit-across-multiple-actions
  (testing "entries from separate owner-patch actions in the same batch all commit"
    (let [commands (#'combat-runtime/edn-owner-patch-commands
                     [{:type :owner-patch
                       :entries [{:path [:resources :cp] :mode :increment :value -1.0}]}
                      {:type :owner-patch
                       :entries [{:path [:ability-data :skill-exps :arc-gen]
                                  :mode :increment :value 0.02}]}])]
      (is (= 2 (count commands)))
      (is (some #(= :consume-resource (:command %)) commands))
      (is (some #(= :add-skill-exp (:command %)) commands)))))

;; guarded-owner-patch-composite-applies-entries (bug #16) removed: schema v2
;; deleted ac/combat/components/guarded_owner_patch.edn once thunder-clap
;; (its only caller) was rewritten to use :cost/spend instead -- the bug the
;; test locked down no longer has a component to reproduce it in.

(deftest instant-ability-does-not-leave-residual-session
  (testing "arc-gen (instant) never leaves a session behind, even on the insufficient-resource branch (bug #20)"
    (runtime-store/get-or-create-player-state!
     player-state-support/test-session-id "p-instant-leak")
    (let [result (combat-runtime/dispatch-intent!
                  "p-instant-leak" {:action :start :ability-id :arc-gen})]
      (is (= :accepted (:status result)))
      (is (= :insufficient-resource (:outcome result)))
      (is (not (combat-sessions/active? "p-instant-leak"))))))

(deftest caster-facade-exposes-neutral-capability-names
  (testing "the schema v2 :from table (design C) maps AC's context shape into neutral capability names"
    (let [facade (#'combat-runtime/caster-facade
                  "owner-1"
                  {:eye-pos {:x 1.0 :y 2.0 :z 3.0}
                   :look {:x 0.0 :y 0.0 :z 1.0}
                   :world-id "world-a"
                   :hold-ticks 42})]
      (is (= {:x 1.0 :y 2.0 :z 3.0} (:caster/eye facade)))
      (is (= {:x 0.0 :y 0.0 :z 1.0} (:caster/aim facade)))
      (is (= "owner-1" (:caster/id facade)))
      (is (= "world-a" (:world/id facade)))
      (is (= 42 (:charge/ticks facade))))))

(deftest activation-seed-varies-across-activations
  (testing "each railgun activation gets its own RNG seed, not a constant hash of [owner ability-id] (bug #21)"
    (runtime-store/get-or-create-player-state!
     player-state-support/test-session-id "p-seed-a")
    (runtime-store/get-or-create-player-state!
     player-state-support/test-session-id "p-seed-b")
    (combat-runtime/dispatch-intent! "p-seed-a" {:action :start :ability-id :railgun})
    (combat-runtime/dispatch-intent! "p-seed-b" {:action :start :ability-id :railgun})
    (let [seed-a (:activation-seed (combat-sessions/session "p-seed-a"))
          seed-b (:activation-seed (combat-sessions/session "p-seed-b"))]
      (is (some? seed-a))
      (is (some? seed-b))
      (is (not= seed-a seed-b))
      (is (not= seed-a (hash ["p-seed-a" :railgun])))
      (is (not= seed-b (hash ["p-seed-b" :railgun]))))))
