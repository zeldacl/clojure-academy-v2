(ns cn.li.vfx.scene-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.scene :as scene]
            [cn.li.mcmod.runtime.effect-emit :as emit]))

(def ^:private arc-strike-scene
  "{:id :arc-strike-scene
    :do [(beam {:start ?start :end ?end :grow-ticks 4})
         (ring {:center ?start :radius 1.0 :segments 16})
         (finish {:outcome :performed})]}")

(deftest compiles-against-the-scene-vocabulary-test
  (let [ir (scene/compile-doc! arc-strike-scene {:start :vec3 :end :vec3})]
    (is (= :arc-strike-scene (:id ir)))))

(deftest samples-real-ops-each-frame-test
  (let [ir (scene/compile-doc! arc-strike-scene {:start :vec3 :end :vec3})
        program (scene/compile-program ir)
        input {:capabilities {:start {:x 0.0 :y 1.0 :z 0.0} :end {:x 0.0 :y 1.0 :z 5.0}
                              :age 0.0 :progress 0.0}}
        ops (scene/sample! program input)]
    (testing "two ops sampled, in declared order"
      (is (= 2 (count ops)))
      (is (= :beam (:kind (first ops))))
      (is (= :ring (:kind (second ops)))))
    (testing "capability values flow through into the constructed op"
      (is (= {:x 0.0 :y 1.0 :z 0.0} (:start (first ops))))
      (is (= {:x 0.0 :y 1.0 :z 5.0} (:end (first ops))))
      (is (= 4 (:grow-ticks (first ops)))))
    (testing "sampling has no side effects -- resampling the same input is idempotent"
      (is (= ops (scene/sample! program input))))))

(deftest sample-into-reuses-a-frame-without-leaking-between-calls-test
  (testing "B5: sample-into! (unlike sample!) takes a caller-owned frame
            it resets and reuses across calls -- prove two calls against
            DIFFERENT inputs, sharing the SAME frame object, each produce
            the correct ops for their own input, matching what sample!
            (fresh frame every call) would have produced."
    (let [ir (scene/compile-doc! arc-strike-scene {:start :vec3 :end :vec3})
          program (scene/compile-program ir)
          input-a {:capabilities {:start {:x 0.0 :y 1.0 :z 0.0} :end {:x 0.0 :y 1.0 :z 5.0}
                                  :age 0.0 :progress 0.0}}
          input-b {:capabilities {:start {:x 9.0 :y 9.0 :z 9.0} :end {:x 1.0 :y 2.0 :z 3.0}
                                  :age 0.0 :progress 0.0}}
          frame (emit/new-frame program nil)
          ops-a (scene/sample-into! program frame input-a)
          ops-b (scene/sample-into! program frame input-b)]
      (is (= (scene/sample! program input-a) ops-a))
      (is (= (scene/sample! program input-b) ops-b))
      (is (not= ops-a ops-b) "sanity: the two inputs really do produce different ops"))))

(deftest v4-render-entry-samples-without-default
  "Regression: compile-v4-document! emits entry :render, not :default.
   sample! must not hardcode :default or client HUD frame sampling throws
   'no such program entry' for every ac/vfx-v4 effect (e.g. beam-arc-fade)."
  (let [doc {:schema :ac/vfx-v4
             :id :tiny-beam
             :lifecycle {:mode :transient}
             :parameters {:start {:type :vec3} :end {:type :vec3}}
             :state {}
             :entry-triggers {:render :vfx/render}
             :phases {:render ['(beam {:start ?start :end ?end :grow-ticks 0})
                               '(finish {:outcome :performed})]}}
        program (scene/compile-v4-document! doc)
        ops (scene/sample! program
                           {:capabilities {:start {:x 0.0 :y 1.0 :z 0.0}
                                           :end {:x 0.0 :y 1.0 :z 5.0}
                                           :age 0.0 :progress 0.0}})]
    (is (= 1 (count ops)))
    (is (= :beam (:kind (first ops))))))

(deftest ring-radius-from-to-map-lerps-before-ring
  "Regression: arc-gen spawns beam-arc-fade with :ring-radius {:from :to}.
   Ring :radius is :double — wiring the map straight in ClassCastException
   at convert. Graph must field+lerp with fade progress first."
  (let [doc {:schema :ac/vfx-v4
             :id :ring-range
             :lifecycle {:mode :transient}
             ;; :map-keys mirrors real beam-arc-fade.edn. It is what lets a
             ;; (:from ?ring-radius) read be type-checked against the input's
             ;; declared shape instead of coming back :any.
             :parameters {:ring-radius {:type :any :map-keys {:from :double :to :double}}
                          :fade-p {:type :double}
                          :center {:type :vec3}}
             :state {}
             :entry-triggers {:render :vfx/render}
             :phases {:render ['(let r (math/lerp (:from ?ring-radius) (:to ?ring-radius) ?fade-p))
                               '(ring {:center ?center :radius r :segments 8 :alpha 1.0})
                               '(finish {:outcome :performed})]}}
        program (scene/compile-v4-document! doc)
        ops (scene/sample! program
                           {:capabilities {:ring-radius {:from 0.12 :to 0.28}
                                           :fade-p 0.5
                                           :center {:x 0.0 :y 1.0 :z 0.0}
                                           :age 0.0 :progress 0.0}})]
    (is (= 1 (count ops)))
    (is (= :ring (:kind (first ops))))
    (is (= 0.2 (:radius (first ops))))))

(deftest each-effect-declares-its-own-user-capability-types-test
  (testing "a scene using an undeclared ?capability is a real compile error,
            same as combat's ?caster/eye -- catches an author typo instead
            of silently reading nil at sample time"
    (let [doc "{:id :bad :do [(beam {:start ?nope :end ?nope :grow-ticks 0}) (finish {:outcome :performed})]}"]
      (is (thrown? clojure.lang.ExceptionInfo (scene/compile-doc! doc {}))))))


