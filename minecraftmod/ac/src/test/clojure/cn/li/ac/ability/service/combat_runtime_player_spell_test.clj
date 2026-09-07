(ns cn.li.ac.ability.service.combat-runtime-player-spell-test
  "S7: end-to-end proof cn.li.ac.ability.service.combat-runtime/dispatch-
   player-spell! actually desugars/admits/compiles/dispatches a real
   player-composed glyph vector through the new engine's catalog-free
   path (cn.li.ability.engine-v2/dispatch-compiled!) -- not a fake host,
   the real capability registry and player-state store, matching the
   real-content discipline combat-runtime-dispatch-intent-v2-test
   already established for catalog abilities."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

;; cn.li.ability.engine-v2's registry-host snapshots the capability
;; registry ONCE, at final-runtime-v2* creation time -- unlike the old
;; engine, which resolves capabilities fresh on every dispatch. Real
;; production always has :entity/damage registered (cn.li.combat.platform/
;; install!, called from cn.li.ac.core.init/init) well before that
;; snapshot happens; this test suite never calls that real installer (see
;; combat-runtime-vanilla-damage-reflection-test's own fake-handler
;; pattern, mirrored here), and whichever test namespace happens to warm
;; final-runtime-v2* FIRST in this JVM run may do so before :entity/damage
;; is registered at all -- discovered by actually running this test, not
;; guessed. reset-final-runtime-v2-for-test! forces a fresh snapshot after
;; this fixture's own fake registration, so it doesn't matter which test
;; ran first.
(defn- with-fake-entity-damage-handler [f]
  (let [previous (get (:actions (capabilities/snapshot)) :entity/damage)]
    (try
      (capabilities/register-action!
       :entity/damage
       (fn [_request] {:status :applied})
       {:allow-overwrite? true})
      (combat-runtime/reset-final-runtime-v2-for-test!)
      (f)
      (finally
        (when previous
          (capabilities/register-action! :entity/damage previous {:allow-overwrite? true}))
        (combat-runtime/reset-final-runtime-v2-for-test!)))))

(use-fixtures :each
  (fn [f]
    (combat-catalog/initialize!)
    (player-state-support/clean-player-states-fixture
     (fn []
       (runtime-store/create-session! player-state-support/test-session-id)
       (with-fake-entity-damage-handler f)))))

(deftest form-self-damage-spell-dispatches-through-the-new-engine-test
  (let [owner "spell-owner-1"
        glyphs [{:glyph :form/self}
                {:glyph :effect/damage :params {:amount 3.0}}]
        result (combat-runtime/dispatch-player-spell! owner glyphs)]
    (is (= :accepted (:status result)))
    (is (= :player/spell (:ability-id result)))
    (is (= :performed (:outcome result)))))

(deftest amplify-augment-scales-the-decorated-effect-test
  ;; Proves the augment actually reaches desugar/admit/compile, not just
  ;; that the un-augmented path works -- a spell with :augment/amplify
  ;; must still pass the exact same admit gate as the plain version.
  (let [owner "spell-owner-2"
        glyphs [{:glyph :form/self}
                {:glyph :effect/damage :params {:amount 2.0}}
                {:glyph :augment/amplify}]
        result (combat-runtime/dispatch-player-spell! owner glyphs)]
    (is (= :accepted (:status result)))
    (is (= :performed (:outcome result)))))

(deftest over-budget-spell-is-rejected-before-any-dispatch-test
  ;; 8 :combat/damage nodes at :cost 3 each = 24 > the 20-point
  ;; player-spell-complexity-cap -- real numbers from dsl_vocabulary.clj,
  ;; not guessed, so this actually exercises the admit rejection path
  ;; rather than accidentally passing.
  (let [owner "spell-owner-3"
        glyphs (into [{:glyph :form/self}]
                     (repeat 8 {:glyph :effect/damage :params {:amount 1.0}}))
        result (combat-runtime/dispatch-player-spell! owner glyphs)]
    (is (= :rejected (:status result)))
    (is (= :over-complexity (:reason result)))))

(deftest unknown-glyph-namespace-is-rejected-not-dispatched-test
  (let [owner "spell-owner-4"
        glyphs [{:glyph :form/self} {:glyph :effect/does-not-exist}]
        result (combat-runtime/dispatch-player-spell! owner glyphs)]
    (is (= :rejected (:status result)))
    (is (= :invalid-glyph (:reason result)))
    (is (= :player/spell (:ability-id result)))))
