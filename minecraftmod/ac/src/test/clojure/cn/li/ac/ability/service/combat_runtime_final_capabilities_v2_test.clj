(ns cn.li.ac.ability.service.combat-runtime-final-capabilities-v2-test
  "S8: cn.li.ac.ability.service.combat-runtime/final-capabilities-v2
   actually materializes real :costs/:cooldown/:progression/:invariants
   declarations from real ac/skills-v4/*.edn sources (via the old catalog's
   own combat-source, byte-identical to the new files per S6 -- see this
   function's own docstring) into the plain-number ?budget/?cooldown/
   ?progression/?invariant capability values the new engine expects, not
   just that it compiles."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]
            [cn.li.ability.session :as combat-sessions]))

(use-fixtures :each
  (fn [f]
    (combat-catalog/initialize!)
    ;; combat-catalog/initialize! and combat-runtime's own final-runtime-v2*
    ;; (what combat-source actually reads) are two DIFFERENT catalog atoms
    ;; -- final-runtime-v2* is normally populated lazily, on the first real
    ;; dispatch-intent-v2! call, which this test file never makes (it only
    ;; ever calls final-capabilities-v2 directly). Trigger the same
    ;; install path dispatch-intent-v2!'s own lazy-install guard uses.
    (when-not (combat-runtime/final-runtime-v2)
      (combat-runtime/install-ac-host-capabilities!)
      (combat-runtime/initialize-final-runtime-v2!))
    (player-state-support/clean-player-states-fixture
     (fn []
       (runtime-store/create-session! player-state-support/test-session-id)
       (combat-sessions/reset-for-test!)
       (try
         (f)
         (finally
           (combat-sessions/reset-for-test!)))))))

(defn- caps! [owner ability-id session-state]
  (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
  (combat-runtime/final-capabilities-v2 owner ability-id {} 42 session-state))

(deftest vec-reflection-threads-the-same-tunable-through-costs-and-invariants-test
  (testing "vec-reflection's :costs :activate :overload and :invariants :overload-floor
           both reference the SAME tunable (:overload-keep) -- must resolve identically"
    (let [caps (caps! "cap-owner-1" :vec-reflection {})]
      (is (= (get-in caps [:budget/activate :resources :overload])
             (:invariant/overload-floor caps)))
      (is (number? (:invariant/overload-floor caps)))
      (is (number? (:progression/reflect-entity caps))))))

(deftest railgun-progression-hit-depends-on-passed-in-session-state-test
  (testing "railgun's :progression :hit selects between :exp-hit/:exp-reflection-hit based
           on :state :reflection-hit? -- final-capabilities-v2 must read the CALLER-
           supplied session-state (pre-dispatch, matching :ability-state-provider's own
           contract), never a same-dispatch write (see railgun.edn's own S8 bug-fix docstring
           for why the DSL itself no longer relies on this capability being state-aware
           mid-dispatch; this test just proves the capability materializes correctly at all)"
    (let [not-reflected (:progression/hit (caps! "cap-owner-2" :railgun {:reflection-hit? false}))
          reflected (:progression/hit (caps! "cap-owner-2" :railgun {:reflection-hit? true}))]
      (is (number? not-reflected))
      (is (number? reflected))
      (is (not= not-reflected reflected)
          "the two tunables (:exp-hit vs :exp-reflection-hit) are configured to different values"))))

(deftest railgun-cooldown-main-is-a-plain-long-test
  (let [caps (caps! "cap-owner-3" :railgun {})]
    (is (integer? (:cooldown/main caps)))))

(deftest caster-facade-fields-are-still-present-test
  (let [caps (caps! "cap-owner-4" :vec-reflection {})]
    (is (= "cap-owner-4" (:caster/id caps)))
    (is (contains? caps :caster/eye))
    (is (contains? caps :caster/aim))))

(deftest metal-block-targeting-fields-are-aliased-to-caster-namespace-test
  (testing "caster-facade calls these :targeting/*; run.clj's fixed-capabilities table
           (added during mag_movement.edn's S6 conversion) calls them :caster/* -- both
           established independently, aliased here rather than renamed"
    (let [caps (caps! "cap-owner-5" :mag-movement {})]
      (is (contains? caps :caster/normal-metal-blocks))
      (is (contains? caps :caster/weak-metal-blocks))
      (is (contains? caps :caster/metal-entities))
      (is (= (:targeting/normal-metal-blocks caps) (:caster/normal-metal-blocks caps))))))

(deftest passive-ability-with-nil-declarations-produces-no-budget-cooldown-progression-keys-test
  (testing "rad-intensify declares :costs/:cooldown/:progression/:invariants nil -- no
           :budget/:cooldown/:invariant keys at all. :progression/mastery and
           :progression/level ARE still present (caster-facade's own raw pre-curve
           mastery/level capabilities, unrelated to this ability's own :progression
           declaration -- excluded here, not a gap)"
    (let [caps (caps! "cap-owner-6" :rad-intensify {})]
      (is (not-any? #(= "budget" (namespace %)) (keys caps)))
      (is (not-any? #(= "cooldown" (namespace %)) (keys caps)))
      (is (= #{:progression/mastery :progression/level}
             (set (filter #(= "progression" (namespace %)) (keys caps))))
          "the only progression keys present are caster-facade's own, not per-ability ones")
      (is (not-any? #(= "invariant" (namespace %)) (keys caps))))))

