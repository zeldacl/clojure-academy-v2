(ns cn.li.ac.ability.service.combat-runtime-edn-fixes-test
  "Regression coverage for the Phase 0 EDN v1 bug fixes: multi-entry
   owner-patch commit, guarded_owner_patch entries application, instant
   ability session leak, and per-activation RNG seed generation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ability.engine :as final-runtime]
            [cn.li.ability.session :as combat-sessions]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

;; See the identical helper in combat_runtime_edn_activation_smoke_test.clj
;; for the full rationale: activation-seed-varies-across-activations below
;; dispatches a real :railgun :start, which reaches a :target/entities node
;; needing the :entity/select host capability that only production bootstrap
;; (cn.li.combat.platform/install!, never called by an ac unit test) would
;; normally register.
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
                  "p-instant-leak" {:op :start :ability-id :arc-gen})]
      (is (= :accepted (:status result)))
      (is (= :insufficient-resource (:outcome result)))
      (is (not (combat-sessions/active? :ac "p-instant-leak"))))))

(deftest caster-facade-exposes-neutral-capability-names
  (testing "the schema v2 :from table (design C) maps AC's context shape into neutral capability names"
    (let [facade (#'combat-runtime/caster-facade
                  "owner-1"
                  ;; :ability-id is required here too, matching every real
                  ;; caller: activation-context (the only production builder
                  ;; of this map) always sets it, and skill-config/destroy-
                  ;; blocks-enabled? -- read via :ability/destroy-blocks?
                  ;; below -- calls (name ability-id) unconditionally.
                  {:ability-id :arc-gen
                   :eye-pos {:x 1.0 :y 2.0 :z 3.0}
                   :look {:x 0.0 :y 0.0 :z 1.0}
                   :world-id "world-a"
                   :hold-ticks 42})]
      (is (= {:x 1.0 :y 2.0 :z 3.0} (:caster/eye facade)))
      (is (= {:x 0.0 :y 0.0 :z 1.0} (:caster/aim facade)))
      (is (= "owner-1" (:caster/id facade)))
      (is (= "world-a" (:world/id facade)))
      (is (= 42 (:charge/ticks facade))))))

(deftest server-hold-ticks-enter-neutral-capability-context
  (testing "authoritative pulse/release hold duration is exposed as :charge/ticks"
    (let [context (#'combat-runtime/activation-context
                   "p-hold" :thunder-clap {:hold-ticks 42} 7)]
      (is (= 42 (:hold-ticks context))))))
(deftest toggle-close-edge-test
  (testing "a second :start on an active :toggle session resolves to the close edge"
    (is (true? (#'combat-runtime/toggle-close-edge? :start :toggle :flashing :flashing))))
  (testing "no active session for this ability -- not a close edge"
    (is (false? (#'combat-runtime/toggle-close-edge? :start :toggle nil :flashing)))
    (is (false? (#'combat-runtime/toggle-close-edge? :start :toggle :other-ability :flashing))))
  (testing "not a :toggle activation -- never a close edge, even with a matching session"
    (is (false? (#'combat-runtime/toggle-close-edge? :start :session :railgun :railgun))))
  (testing "not a :start op -- never a close edge"
    (is (false? (#'combat-runtime/toggle-close-edge? :pulse :toggle :flashing :flashing)))))

(deftest should-open-session-test
  (testing ":session and :toggle both open a session on an accepted :start"
    (is (true? (#'combat-runtime/should-open-session? :accepted :start :session false false)))
    (is (true? (#'combat-runtime/should-open-session? :accepted :start :toggle false false))))
  (testing ":instant and :passive never open a session"
    (is (false? (#'combat-runtime/should-open-session? :accepted :start :instant false false)))
    (is (false? (#'combat-runtime/should-open-session? :accepted :start :passive false false))))
  (testing "a rejected/non-:start result never opens a session"
    (is (false? (#'combat-runtime/should-open-session? :rejected :start :session false false)))
    (is (false? (#'combat-runtime/should-open-session? :accepted :pulse :session false false))))
  (testing "an ability that finished immediately or is already active does not (re-)open one"
    (is (false? (#'combat-runtime/should-open-session? :accepted :start :session true false)))
    (is (false? (#'combat-runtime/should-open-session? :accepted :start :session false true)))))

(deftest activation-seed-varies-across-activations
  (testing "each railgun activation gets its own RNG seed, not a constant hash of [owner ability-id] (bug #21)"
    (runtime-store/get-or-create-player-state!
     player-state-support/test-session-id "p-seed-a")
    (runtime-store/get-or-create-player-state!
     player-state-support/test-session-id "p-seed-b")
    (combat-runtime/dispatch-intent! "p-seed-a" {:op :start :ability-id :railgun})
    (combat-runtime/dispatch-intent! "p-seed-b" {:op :start :ability-id :railgun})
    (let [seed-a (:activation-seed (combat-sessions/session :ac "p-seed-a"))
          seed-b (:activation-seed (combat-sessions/session :ac "p-seed-b"))]
      (is (some? seed-a))
      (is (some? seed-b))
      (is (not= seed-a seed-b))
      (is (not= seed-a (hash ["p-seed-a" :railgun])))
      (is (not= seed-b (hash ["p-seed-b" :railgun]))))))

