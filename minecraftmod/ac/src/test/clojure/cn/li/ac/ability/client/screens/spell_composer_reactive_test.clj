(ns cn.li.ac.ability.client.screens.spell-composer-reactive-test
  "Pure state coverage for the grouped player spell composer."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.screens.spell-composer-reactive :as composer]))

(deftest initial-state-has-catalog-and-grouped-empty-composition-test
  (let [state (#'composer/initial-state)]
    (is (seq (:catalog state)))
    (is (seq (:glyph-specs state)))
    (is (nil? (:form state)))
    (is (= [] (:effect-groups state)))
    (is (nil? (:selected-effect state)))))

(deftest cannot-add-effect-or-augment-before-form-or-selection-test
  (let [state (#'composer/initial-state)
        no-effect (#'composer/add-effect state :effect/damage)
        picked (#'composer/pick-form state :form/self)
        no-augment (#'composer/add-augment picked :augment/amplify)]
    (is (= [] (:effect-groups no-effect)))
    (is (= [] (:effect-groups no-augment)))
    (is (= "Select an effect first." (:status no-augment)))))

(deftest grouped-composition-preserves-params-and-augment-order-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/touch)
                  (#'composer/add-effect :effect/damage)
                  (#'composer/add-augment :augment/amplify))
        glyphs (#'composer/composed-glyphs state)]
    (is (= :form/touch (:glyph (first glyphs))))
    (is (= {:range 16.0} (:params (first glyphs))))
    (is (= [:effect/damage :augment/amplify] (mapv :glyph (rest glyphs))))))

(deftest effect-groups-can-be-selected-reordered-and-removed-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage)
                  (#'composer/add-effect :effect/push))
        moved (#'composer/move-effect state 1 -1)
        removed (#'composer/remove-effect moved 0)]
    (is (= :effect/push (:glyph (first (:effect-groups moved)))))
    (is (= 1 (:selected-effect moved)))
    (is (= [:effect/damage] (mapv :glyph (:effect-groups removed))))))

(deftest clear-composition-resets-grouped-state-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/push)
                  (#'composer/clear-composition))]
    (is (nil? (:form state)))
    (is (= [] (:effect-groups state)))
    (is (nil? (:selected-effect state)))))

(deftest render-state-shape-matches-grouped-ui-contract-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage))
        rendered (#'composer/render-state state)]
    (is (vector? (:form-palette rendered)))
    (is (vector? (:effect-palette rendered)))
    (is (vector? (:augment-palette rendered)))
    (is (vector? (:effect-slots rendered)))
    (is (= 1 (count (:effect-slots rendered))))
    (is (true? (:can-cast? rendered)))
    (is (false? (:busy? rendered)))))

(deftest composer-enforces-eight-effect-and-eight-augment-bounds-test
  (let [base (-> (#'composer/initial-state) (#'composer/pick-form :form/self))
        effects (reduce (fn [s _] (#'composer/add-effect s :effect/damage)) base (range 9))
        aug-base (#'composer/add-augment effects :augment/amplify)
        augments (reduce (fn [s _] (#'composer/add-augment s :augment/amplify))
                         aug-base (range 8))]
    (is (= 8 (count (:effect-groups effects))))
    (is (= 8 (count (get-in augments [:effect-groups 7 :augments]))))))

(deftest palette-splits-forms-effects-and-augments-test
  (let [rendered (#'composer/render-state (#'composer/initial-state))]
    (is (= #{"form/self" "form/touch"} (set (map :glyph (:form-palette rendered)))))
    (is (= #{"effect/damage" "effect/push"} (set (map :glyph (:effect-palette rendered)))))
    (is (= #{"augment/amplify"} (set (map :glyph (:glyph (:augment-palette rendered))))))))