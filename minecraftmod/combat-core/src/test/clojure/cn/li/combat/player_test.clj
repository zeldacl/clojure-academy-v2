(ns cn.li.combat.player-test
  "S7: end-to-end proof cn.li.combat.player's desugar -> compile-doc! ->
   admit -> dispatch! pipeline actually works against combat's real
   vocabulary -- not just that desugar produces syntactically valid text.
   Every deftest either dispatches the admitted IR against a fake host
   and asserts the real host calls, or asserts a real admit rejection."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.player :as player]
            [cn.li.combat.run :as run]))

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
