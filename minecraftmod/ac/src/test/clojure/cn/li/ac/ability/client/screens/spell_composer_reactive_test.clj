(ns cn.li.ac.ability.client.screens.spell-composer-reactive-test
  "Pure state coverage for the grouped player spell composer."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.screens.spell-composer-reactive :as composer]))

(def ^:private spell-composer-ui-path "src/presentation/resources/academy/app/spell_composer.ui.edn")

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
    ;; Moving the selected effect keeps that effect selected at its new index.
    (is (= 0 (:selected-effect moved)))
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

(deftest long-composer-display-labels-are-bounded-test
  (let [long-glyph (apply str (repeat 200 "effect/very-long-name/"))
        clipped (#'composer/ui-label long-glyph 40.0)]
    (is (<= (count clipped) (count long-glyph)))
    (is (.endsWith ^String clipped "..."))))

(deftest cast-with-form-but-no-effect-is-rejected-locally-test
  (let [state* (atom (#'composer/pick-form (#'composer/initial-state) :form/self))]
    (#'composer/handle-action state* nil :composer/cast nil)
    (is (= "Pick a form and at least one effect first." (:status @state*)))
    (is (false? (:busy? @state*)))))

(deftest composer-enforces-eight-effect-and-eight-augment-bounds-test
  (let [base (-> (#'composer/initial-state) (#'composer/pick-form :form/self))
        effects (reduce (fn [s _] (#'composer/add-effect s :effect/damage)) base (range 9))
        aug-base (#'composer/add-augment effects :augment/amplify)
        augments (reduce (fn [s _] (#'composer/add-augment s :augment/amplify))
                         aug-base (range 8))]
    (is (= 8 (count (:effect-groups effects))))
    (is (= 8 (count (get-in augments [:effect-groups 7 :augments]))))))

(deftest malformed-index-actions-are-safe-noops-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage))
        groups (:effect-groups state)]
    (is (= state (#'composer/move-effect state nil -1)))
    (is (= state (#'composer/move-effect state 0 nil)))
    (is (= state (#'composer/remove-augment state nil 0)))
    (is (= state (#'composer/remove-augment state 99 0)))
    (let [invalid-selected (assoc state :selected-effect 99)
          result (#'composer/add-augment invalid-selected :augment/amplify)]
      (is (= groups (:effect-groups result)))
      (is (= "Select an effect first." (:status result))))))

(deftest malformed-parameter-index-actions-are-safe-noops-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage))
        malformed-nil (#'composer/param-submit state {:item {:effect-index nil :param-key :amount}
                                               :value "2"})
        malformed-string (#'composer/param-submit state {:item {:effect-index "bad" :param-key :amount}
                                                  :value "2"})
        malformed-change (#'composer/param-change state {:item {:effect-index 99 :param-key :amount}
                                                         :value "2"})
        malformed-select (#'composer/select-effect state 99)
        malformed-drafts (assoc state :param-drafts {["bad" :amount] "2"})
        state* (atom state)
        groups (:effect-groups state)]
    (is (= "Unknown parameter." (:status malformed-nil)))
    (is (= "Unknown parameter." (:status malformed-string)))
    (is (= state malformed-change))
    (is (= "Select a valid effect." (:status malformed-select)))
    (is (false? (#'composer/valid-param-drafts? malformed-drafts)))
    (#'composer/handle-action state* nil :composer/add-effect {:item {:glyph 99}})
    (is (= groups (:effect-groups @state*)))))

(deftest palette-splits-forms-effects-and-augments-test
  (let [rendered (#'composer/render-state (#'composer/initial-state))]
    (is (= #{"form/self" "form/touch"} (set (map :glyph (:form-palette rendered)))))
    (is (= #{"effect/damage" "effect/push"} (set (map :glyph (:effect-palette rendered)))))
    (is (= #{"augment/amplify"} (set (map :glyph (:augment-palette rendered)))))))

(deftest selected-effect-parameters-can-be-edited-with-bounds-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage))
        changed (#'composer/param-change state {:item {:effect-index 0 :param-key :amount}
                                                :value "7.5"})
        submitted (#'composer/param-submit changed {:item {:effect-index 0 :param-key :amount}
                                                    :value "7.5"})
        rejected (#'composer/param-submit submitted {:item {:effect-index 0 :param-key :amount}
                                                     :value "99"})]
    (is (= "7.5" (get-in changed [:param-drafts [0 :amount]])))
    (is (= 7.5 (get-in submitted [:effect-groups 0 :params :amount])))
    (is (= 7.5 (get-in rejected [:effect-groups 0 :params :amount])))
    (is (.contains ^String (:status rejected) "exceeds"))
    (is (= [{:effect-index 0 :param-key :amount :draft-key :composer-param-0-amount :label "amount [0.0..20.0]" :value "7.5"}]
           (#'composer/selected-param-fields submitted)))))

(deftest effect-reorder-and-remove-remap-parameter-drafts-test
  (let [base (-> (#'composer/initial-state)
                 (#'composer/pick-form :form/self)
                 (#'composer/add-effect :effect/damage)
                 (#'composer/add-effect :effect/push))
        drafted (-> base
                     (#'composer/param-change {:item {:effect-index 0 :param-key :amount}
                                               :value "3.0"})
                     (#'composer/param-change {:item {:effect-index 1 :param-key :strength}
                                               :value "4.0"}))
        moved (#'composer/move-effect drafted 1 -1)
        removed (#'composer/remove-effect moved 0)]
    (is (= "3.0" (get-in moved [:param-drafts [1 :amount]])))
    (is (= "4.0" (get-in moved [:param-drafts [0 :strength]])))
    (is (nil? (get-in moved [:param-drafts [0 :amount]])))
    (is (= "3.0" (get-in removed [:param-drafts [0 :amount]])))
    (is (nil? (get-in removed [:param-drafts [0 :strength]])))))

(deftest invalid-in-progress-draft-cannot-be-cast-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage)
                  (#'composer/param-change {:item {:effect-index 0 :param-key :amount}
                                            :value "not-a-number"}))]
    (is (false? (#'composer/valid-param-drafts? state)))
    (is (false? (:can-cast? (#'composer/render-state state))))) )

(deftest rendered-effect-slot-exposes-augment-remove-payload-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage)
                  (#'composer/add-augment :augment/amplify))
        slot (first (:effect-slots (#'composer/render-state state)))
        augment (first (:augments slot))]
    (is (= "+ augment/amplify" (:label augment)))
    (is (= 0 (:effect-index augment)))
    (is (= 0 (:augment-index augment)))
    (is (= "X" (:remove-label augment)))
    (is (= [] (get-in (#'composer/remove-augment state 0 0) [:effect-groups 0 :augments])))))
(deftest rendered-augment-rows-use-dynamic-vertical-budget-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage)
                  (#'composer/add-augment :augment/amplify)
                  (#'composer/add-augment :augment/amplify))
        slot (first (:effect-slots (#'composer/render-state state)))]
    (is (= 28 (:augment-height slot)))
    (is (= 44 (:row-height slot)))
    (is (= 2 (count (:augments slot))))))
(deftest spell-composer-layout-fits-design-and-320x240-bounds-test
  (let [ui (binding [*read-eval* false] (read-string (slurp spell-composer-ui-path)))
        host (:host ui)
        root-layout (get-in ui [:root :layout])
        all-maps (filter map? (tree-seq coll? seq ui))
        augment-repeaters (filter #(and (= :repeater (:type %))
                                        (= [:item :augments] (get-in % [:bind :items]))) all-maps)
        effect-slot-columns (filter #(and (= :column (:type %))
                                          (= [:item :row-height] (get-in % [:bind :height]))) all-maps)
        scale (min (/ 320.0 (double (:design-width host)))
                   (/ 240.0 (double (:design-height host))))
        right (* scale (+ (double (:x root-layout)) (double (:width root-layout))))
        bottom (* scale (+ (double (:y root-layout)) (double (:height root-layout))))]
    (is (= :screen (:kind host)))
    (is (= 1 (count augment-repeaters))
        "augment repeater must be a single explicit collection")
    (is (= :column (get-in (first augment-repeaters) [:layout :direction]))
        "augment entries must stack vertically instead of overflowing horizontally")
    (is (= [:item :augment-height] (get-in (first augment-repeaters) [:bind :height])))
    (is (= 1 (count effect-slot-columns))
        "effect slot height must be driven by its augment rows")
    (is (<= (+ (double (:x root-layout)) (double (:width root-layout)))
            (double (:design-width host)))
        "composer root must fit its declared design width")
    (is (<= (+ (double (:y root-layout)) (double (:height root-layout)))
            (double (:design-height host)))
        "composer root must fit its declared design height")
    (is (<= right 320.0) (str "composer right edge exceeds 320px: " right))
    (is (<= bottom 240.0) (str "composer bottom edge exceeds 240px: " bottom))))


(deftest effect-reorder-controls-bind-boundary-visibility-test
  (let [ui (binding [*read-eval* false] (read-string (slurp spell-composer-ui-path)))
        buttons (filter #(and (= :button (:type %))
                              (contains? #{:composer/move-effect-up
                                           :composer/move-effect-down}
                                         (get-in % [:on :activate])))
                        (tree-seq coll? seq ui))
        by-action (into {} (map (fn [button] [(get-in button [:on :activate]) button]) buttons))]
    (is (= 2 (count buttons)))
    (is (= [:item :can-move-up?]
           (get-in by-action [:composer/move-effect-up :bind :visible])))
    (is (= [:item :can-move-down?]
           (get-in by-action [:composer/move-effect-down :bind :visible])))))
