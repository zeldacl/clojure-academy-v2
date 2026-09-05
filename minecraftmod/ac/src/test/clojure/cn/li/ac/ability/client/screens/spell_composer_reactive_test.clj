(ns cn.li.ac.ability.client.screens.spell-composer-reactive-test
  "Unit coverage for the spell composer's PURE state logic (form/effect
   picking, render-state shaping) -- everything reachable without a live
   presentation-runtime mount or a real network round-trip. open! and
   the actual req-submit-spell! callback are exercised only by using the
   screen in-game; see the namespace's own docstring for why."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.screens.spell-composer-reactive :as composer]))

(deftest initial-state-has-a-real-catalog-and-no-composition-test
  (let [state (#'composer/initial-state)]
    (is (seq (:catalog state)))
    (is (nil? (:form state)))
    (is (= [] (:effects state)))))

(deftest cannot-add-an-effect-before-picking-a-form-test
  (let [state (#'composer/initial-state)
        after (#'composer/add-effect state :effect/damage)]
    (is (= [] (:effects after)))
    (is (nil? (:form after)))))

(deftest picking-a-form-then-adding-effects-builds-a-legal-glyph-sequence-test
  (let [state (-> (#'composer/initial-state)
                   (#'composer/pick-form :form/touch)
                   (#'composer/add-effect :effect/damage)
                   (#'composer/add-effect :augment/amplify))
        glyphs (#'composer/composed-glyphs state)]
    (is (= [{:glyph :form/touch} {:glyph :effect/damage} {:glyph :augment/amplify}] glyphs))))

(deftest composed-glyphs-is-nil-without-a-form-test
  (is (nil? (#'composer/composed-glyphs (#'composer/initial-state)))))

(deftest clear-composition-resets-form-and-effects-test
  (let [state (-> (#'composer/initial-state)
                   (#'composer/pick-form :form/self)
                   (#'composer/add-effect :effect/push)
                   (#'composer/clear-composition))]
    (is (nil? (:form state)))
    (is (= [] (:effects state)))))

(deftest render-state-shape-is-consistent-with-the-ui-edn-state-schema-test
  (let [state (-> (#'composer/initial-state) (#'composer/pick-form :form/self))
        rendered (#'composer/render-state state)]
    (is (string? (:title rendered)))
    (is (vector? (:form-palette rendered)))
    (is (vector? (:effect-palette rendered)))
    (is (string? (:form-label rendered)))
    (is (vector? (:effect-slots rendered)))
    (is (boolean? (:can-cast? rendered)))
    (is (false? (:can-cast? rendered)) "no effects picked yet")
    (is (string? (:status rendered)))
    (is (= "Cast" (:cast-label rendered)))
    (is (= "Clear" (:clear-label rendered)))))

(deftest render-state-can-cast-only-once-form-and-at-least-one-effect-are-set-test
  (let [state (-> (#'composer/initial-state)
                   (#'composer/pick-form :form/self)
                   (#'composer/add-effect :effect/damage))
        rendered (#'composer/render-state state)]
    (is (true? (:can-cast? rendered)))))

(deftest palette-splits-forms-from-effects-and-augments-test
  (let [state (#'composer/initial-state)
        rendered (#'composer/render-state state)
        form-glyphs (set (map :glyph (:form-palette rendered)))
        effect-glyphs (set (map :glyph (:effect-palette rendered)))]
    (is (= #{"form/self" "form/touch"} form-glyphs))
    (is (= #{"effect/damage" "effect/push" "augment/amplify"} effect-glyphs))))
