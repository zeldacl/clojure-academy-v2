(ns cn.li.ac.ability.client.screens.spell-composer-reactive-test
  "Pure state coverage for the grouped player spell composer."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ac.ability.client.screens.spell-composer-reactive :as composer]))

;; i18n/*translate-fn* defaults to (fn [k _] (str k)) with no platform
;; module installed (never true in this headless test process) -- real key
;; resolution is spell-glyph-translations-test's job. This binding only
;; proves glyph-label actually threads a glyph's :i18n key through
;; i18n/translate, by making the "translation" a recognizable transform of
;; the key instead of the identity default.
(defmacro ^:private with-fake-translations [& body]
  `(binding [i18n/*translate-fn* (fn [k# _args#] (str "T:" k#))]
     ~@body))

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
    (is (vector? (:palette-rows rendered)))
    (is (vector? (:chain-items rendered)))
    ;; form card + one effect card
    (is (= 2 (count (:chain-items rendered))))
    (is (true? (:form? (first (:chain-items rendered)))))
    (is (true? (:effect? (second (:chain-items rendered)))))
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

(deftest palette-groups-forms-effects-and-augments-under-one-list-test
  (let [rows (:palette-rows (#'composer/render-state (#'composer/initial-state)))
        headers (filter :header? rows)
        entries (filter :entry? rows)
        glyphs-under (fn [kind]
                       (->> rows
                            (drop-while #(not= kind (:kind %)))
                            (rest)
                            (take-while #(not (:header? %)))
                            (map :glyph)
                            set))]
    ;; C3: every catalog entry renders (grey-out, never filtered/hidden).
    (is (= #{:form :effect :augment} (set (map :kind headers))))
    (is (= 5 (count entries)))
    (is (= #{:form/self :form/touch} (glyphs-under :form)))
    (is (= #{:effect/damage :effect/push} (glyphs-under :effect)))
    (is (= #{:augment/amplify} (glyphs-under :augment)))
    (is (every? :admissible? entries) "sanity: every glyph is admissible with no form picked yet")))

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
    (is (= [{:effect-index 0 :param-key :amount :draft-key :composer-param-0-amount :label "amount" :value "7.5"
             :decrement-label "-" :increment-label "+"}]
           (#'composer/selected-param-fields submitted)))))

(deftest param-stepper-nudges-value-within-descriptor-bounds-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage))
        up (#'composer/param-step state 0 :amount 1)
        clamped-low (#'composer/param-step state 0 :amount -1000)
        clamped-high (#'composer/param-step state 0 :amount 1000)]
    (is (> (get-in up [:effect-groups 0 :params :amount])
           (get-in state [:effect-groups 0 :params :amount])))
    (is (= 0.0 (get-in clamped-low [:effect-groups 0 :params :amount])))
    (is (= 20.0 (get-in clamped-high [:effect-groups 0 :params :amount])))))

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

(deftest rendered-chain-card-exposes-augment-remove-payload-test
  (with-fake-translations
    (let [state (-> (#'composer/initial-state)
                    (#'composer/pick-form :form/self)
                    (#'composer/add-effect :effect/damage)
                    (#'composer/add-augment :augment/amplify))
          card (second (:chain-items (#'composer/render-state state)))
          augment (first (:augments card))]
      (is (str/starts-with? (:label augment) "T:") "augment label goes through i18n/translate, not a raw keyword")
      (is (= 0 (:effect-index augment)))
      (is (= 0 (:augment-index augment)))
      (is (= "x" (:remove-label augment)))
      (is (= [] (get-in (#'composer/remove-augment state 0 0) [:effect-groups 0 :augments]))))))

;; C1/C2: palette + chain labels are localized display names, never the raw
;; "kind/name" keyword text a player would have no reason to understand.
(deftest palette-and-chain-labels-are-never-raw-glyph-keywords-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/touch)
                  (#'composer/add-effect :effect/damage))
        rendered (#'composer/render-state state)
        palette-labels (map :label (filter :entry? (:palette-rows rendered)))
        chain-labels (map :label (:chain-items rendered))]
    (is (seq palette-labels))
    (is (not-any? #(str/includes? % "/") palette-labels))
    (is (not-any? #(str/includes? % "/") chain-labels))))

;; C4: the complexity readout is live in render-state, not only computed
;; once at Cast time.
(deftest complexity-readout-updates-as-effects-are-added-test
  (let [empty-rendered (#'composer/render-state (#'composer/initial-state))
        with-effect (-> (#'composer/initial-state)
                        (#'composer/pick-form :form/self)
                        (#'composer/add-effect :effect/damage))
        rendered (#'composer/render-state with-effect)]
    (is (= 0.0 (:complexity-ratio empty-rendered)))
    (is (false? (:over-cap? empty-rendered)))
    (is (pos? (:complexity-ratio rendered)))
    (is (<= 0.0 (:complexity-ratio rendered) 1.0))
    (is (str/includes? (:complexity-label rendered) "Complexity"))))

(deftest rendered-chain-card-lists-every-augment-in-order-test
  (let [state (-> (#'composer/initial-state)
                  (#'composer/pick-form :form/self)
                  (#'composer/add-effect :effect/damage)
                  (#'composer/add-augment :augment/amplify)
                  (#'composer/add-augment :augment/amplify))
        card (second (:chain-items (#'composer/render-state state)))]
    (is (= 2 (count (:augments card))))
    (is (= [0 1] (mapv :augment-index (:augments card))))))
(deftest spell-composer-uses-the-shared-editor-shell-test
  (let [ui (binding [*read-eval* false] (read-string (slurp spell-composer-ui-path)))
        host (:host ui)
        root (:root ui)
        all-maps (filter map? (tree-seq coll? seq ui))
        chain-repeater (first (filter #(= [:state :chain-items] (get-in % [:bind :items])) all-maps))
        augment-repeaters (filter #(= [:item :augments] (get-in % [:bind :items])) all-maps)]
    (is (= :screen (:kind host)))
    ;; P3: "do not enlarge" -- unlike the node editor, this design size did
    ;; not move; P0's centering-not-scaling finding is the reason any
    ;; growth here would need the same real-viewport justification.
    (is (= 480 (:design-width host)))
    (is (= 320 (:design-height host)))
    (is (= :fit (:scale-policy host)))
    (is (= :include (keyword (name (:type root)))))
    (is (= "academy/shared/editor_shell" (:src root)))
    (is (= :row (get-in chain-repeater [:layout :direction]))
        "the spell chain reads left-to-right, Form -> Effect -> Effect+Aug")
    (is (= 1 (count augment-repeaters)))
    (is (= :column (get-in (first augment-repeaters) [:layout :direction]))
        "augments stack vertically inside their own card, not sideways")))


;; Layout regression (P1 finding): the old three-panel layout had two
;; separately-titled "Effects" panels with opposite meanings (already-
;; equipped slots vs. the add-effect palette). The unified palette has no
;; static "Effects" literal anywhere -- it labels each group from the
;; glyph's own :kind at render time.
(deftest no-static-effects-title-literal-in-source-test
  (let [ui (binding [*read-eval* false] (read-string (slurp spell-composer-ui-path)))
        all-maps (filter map? (tree-seq coll? seq ui))
        literal-texts (keep :text all-maps)]
    (is (not-any? #(and (string? %) (= "Effects" %)) literal-texts))))

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
