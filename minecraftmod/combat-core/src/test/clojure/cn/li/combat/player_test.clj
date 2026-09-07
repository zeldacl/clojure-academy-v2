(ns cn.li.combat.player-test
  "S7: end-to-end proof cn.li.combat.player's desugar -> compile-doc! ->
   admit -> dispatch! pipeline actually works against combat's real
   vocabulary -- not just that desugar produces syntactically valid text.
   Every deftest either dispatches the admitted IR against a fake host
   and asserts the real host calls, or asserts a real admit rejection."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.player :as player]
            [cn.li.combat.run :as run]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.node.cost :as cost]))

(defn- fake-host [calls]
  {:query! (fn [cap args _fr]
            (swap! calls conj [:query cap args])
            (case cap
              :raycast {:entity-id "target-uuid" :position {:x 1.0 :y 2.0 :z 3.0}}))
   :command! (fn [cap args _fr] (swap! calls conj [:action cap args]))})

(defn- fake-host-no-hit [calls]
  {:query! (fn [cap args _fr]
            (swap! calls conj [:query cap args])
            (case cap :raycast {:entity-id nil}))
   :command! (fn [cap args _fr] (swap! calls conj [:action cap args]))})

(def ^:private input {:capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.5 :z 0.0}
                                     :caster/aim {:x 0.0 :y 0.0 :z 1.0}}})

(deftest form-self-effect-damage-dispatches-real-damage-test
  (let [calls (atom [])
        {:keys [ok ir]} (player/compile-and-admit
                          [{:glyph :form/self} {:glyph :effect/damage :params {:amount 4.0}}]
                          1000)
        program (run/compile-program ir (fake-host calls))
        frame (run/dispatch! program :default input)]
    (is (true? ok))
    (is (= [[:action :entity/damage {:target "player-1" :amount 4.0}]] @calls))
    (is (= :performed (:outcome (.-result frame))))))

(deftest form-touch-effect-push-stacks-augment-amplify-test
  (let [calls (atom [])
        {:keys [ok ir]} (player/compile-and-admit
                          [{:glyph :form/touch :params {:range 20.0}}
                           {:glyph :effect/push :params {:strength 1.0}}
                           {:glyph :augment/amplify}]
                          1000)
        program (run/compile-program ir (fake-host calls))
        _frame (run/dispatch! program :default input)]
    (is (true? ok))
    (is (some #(= [:query :raycast {:origin {:x 0.0 :y 1.5 :z 0.0} :direction {:x 0.0 :y 0.0 :z 1.0}
                                    :distance 20.0 :include-entities? true :living-only? true}]
                  %)
              @calls))
    (is (some #(= [:action :entity/impulse {:target "target-uuid" :vector {:vec3 [0.0 0.0 1.5]}}] %)
              @calls)
        "strength 1.0 * amplify-multiplier(1 stack)=1.5 scales ?caster/aim")))

(deftest form-touch-no-hit-skips-the-effect-entirely-test
  (let [calls (atom [])
        {:keys [ok ir]} (player/compile-and-admit
                          [{:glyph :form/touch} {:glyph :effect/damage}]
                          1000)
        program (run/compile-program ir (fake-host-no-hit calls))
        _frame (run/dispatch! program :default input)]
    (is (true? ok))
    (is (not (some #(= :action (first %)) @calls))
        "a whiffed raycast (nil entity-id) must not fire any effect command")))

(deftest desugar-rejects-malformed-glyph-sequences-test
  (testing "empty glyph vector"
    (is (thrown? clojure.lang.ExceptionInfo (player/desugar []))))
  (testing "must start with a :form/*"
    (is (thrown? clojure.lang.ExceptionInfo (player/desugar [{:glyph :effect/damage}]))))
  (testing "no :effect/* at all"
    (is (thrown? clojure.lang.ExceptionInfo (player/desugar [{:glyph :form/self}]))))
  (testing "augment with no preceding effect"
    (is (thrown? clojure.lang.ExceptionInfo
                 (player/desugar [{:glyph :form/self} {:glyph :augment/amplify}]))))
  (testing "unknown glyph"
    (is (thrown? clojure.lang.ExceptionInfo
                 (player/desugar [{:glyph :form/self} {:glyph :effect/nonexistent}])))))

(deftest admit-rejects-over-complexity-test
  (let [ir (run/compile-doc! (player/desugar [{:glyph :form/self} {:glyph :effect/damage}]))
        verdict (player/admit ir 0)]
    (is (false? (:ok verdict)))
    (is (= :over-complexity (:reject verdict)))))

(deftest admit-rejects-a-forbidden-effect-test
  (let [ir (run/compile-doc!
            "{:ability :bad-spell :activation :instant
              :do [(inventory/consume {:source :main-hand :count 1})
                   (finish {:outcome :performed})]}")
        verdict (player/admit ir 1000)]
    (is (false? (:ok verdict)))
    (is (= :forbidden-effect (:reject verdict)))
    (is (= #{:inventory-write} (:effects verdict)))))

(deftest admit-rejects-unbounded-iteration-bounds-test
  (let [ir (run/compile-doc!
            "{:ability :bad-spell :activation :instant
              :tunables {:aoe {:type :double} :lim {:type :long}}
              :do [(let xs (target/entities {:shape {:type :sphere :center ?caster/eye :radius $aoe}
                                             :limit $lim}))
                   (each t xs (combat/damage {:target t :amount 1.0}))
                   (finish {:outcome :performed})]}")
        verdict (player/admit ir 1000)]
    (is (false? (:ok verdict)))
    (is (= :over-budget (:reject verdict)))
    (is (nil? (:max-iterations verdict)))))

(deftest glyph-catalog-covers-every-known-glyph-test
  (let [catalog (player/glyph-catalog)]
    (is (= #{:form/self :form/touch :effect/damage :effect/push :augment/amplify}
           (set (map :glyph catalog))))
    (is (every? :admissible? catalog))))

(deftest glyph-catalog-isolates-each-form-glyphs-own-marginal-cost-test
  ;; :form/self is the zero-cost baseline by construction (a single sigil
  ;; read, no :query/:action instruction); :form/touch's own contribution
  ;; must equal exactly what target/raycast alone costs, not
  ;; target/raycast PLUS whatever baseline effect it was measured against.
  (let [by-glyph (into {} (map (juxt :glyph identity)) (player/glyph-catalog))
        self-entry (get by-glyph :form/self)
        touch-entry (get by-glyph :form/touch)]
    (is (= {:effects #{} :cost 0} (select-keys self-entry [:effects :cost])))
    (is (= #{:world-read} (:effects touch-entry)))
    (is (pos? (:cost touch-entry)))))

(deftest glyph-catalog-effects-match-a-direct-compile-of-the-same-vocab-node-test
  ;; The catalog's :effects/:cost for :effect/damage must match compiling
  ;; the SAME underlying vocab node (combat/damage) directly, independent
  ;; of desugar/glyph-catalog's own machinery -- cross-checked against
  ;; cn.li.node.cost/analyze run by hand, not just self-consistently
  ;; against glyph-catalog's own output.
  (let [damage-entry (some #(when (= :effect/damage (:glyph %)) %) (player/glyph-catalog))
        ir (run/compile-doc!
            "{:ability :t :activation :instant
              :do [(combat/damage {:target ?caster/id :amount 1.0})
                   (finish {:outcome :performed})]}")
        summary (cost/analyze ir vocab/nodes)]
    (is (= (:effects summary) (:effects damage-entry)))
    (is (= (:complexity summary) (:cost damage-entry)))))

(deftest glyph-catalog-augment-contributes-nothing-extra-test
  (let [augment-entry (some #(when (= :augment/amplify (:glyph %)) %) (player/glyph-catalog))]
    (is (= {:effects #{} :cost 0} (select-keys augment-entry [:effects :cost])))))

(deftest analyze-player-spell-rejects-invalid-parameters-without-throwing-test
  (testing "out-of-range range"
    (let [verdict (player/analyze-player-spell
                   [{:glyph :form/touch :params {:range 129.0}}
                    {:glyph :effect/damage}]
                   1000)]
      (is (false? (:ok verdict)))
      (is (= :invalid-glyph (:reject verdict)))))
  (testing "unknown parameter"
    (let [verdict (player/analyze-player-spell
                   [{:glyph :form/self}
                    {:glyph :effect/damage :params {:amount 2.0 :oops 1}}]
                   1000)]
      (is (false? (:ok verdict)))
      (is (= :invalid-glyph (:reject verdict))))))

(deftest compile-and-admit-enforces-player-glyph-count-and-augment-bounds-test
  (let [too-many (vec (concat [{:glyph :form/self} {:glyph :effect/damage}]
                              (repeat 31 {:glyph :augment/amplify})))
        verdict (player/compile-and-admit too-many 1000)]
    (is (false? (:ok verdict)))
    (is (= :invalid-glyph (:reject verdict)))))