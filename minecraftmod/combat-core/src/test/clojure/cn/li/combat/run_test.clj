(ns cn.li.combat.run-test
  "End-to-end proof the new pipeline (surface DSL -> cn.li.node.compile IR
   -> cn.li.mcmod.runtime.effect-emit CompiledProgram -> dispatch!) works
   against combat's REAL vocabulary (cn.li.combat.dsl-vocabulary), not a
   toy fixture: a representative ability exercising raycast, a conditional
   damage action, vfx!, and cooldown/start, actually dispatched against a
   fake host and its outbox/result asserted."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.run :as run]))

(def ^:private thunder-bolt-ish
  "{:ability :thunder-bolt-ish
    :activation :instant
    :tunables {:range {:type :double} :damage {:type :double}}
    :do
    [(let hit (target/raycast {:origin ?caster/eye :direction ?caster/aim :distance $range
                               :include-entities? true :include-blocks? true :living-only? true}))
     (when (:entity-id hit)
       (combat/damage {:target (:entity-id hit) :amount $damage}))
     (vfx! {:effect-id :arc-strike-transient :operation :spawn :start ?caster/eye})
     (cooldown/start {:name :main :ticks 40})
     (finish {:outcome :performed :end-ability? true})]}")

(defn- fake-host [calls]
  {:query! (fn [cap args _fr]
            (swap! calls conj [:query cap args])
            (case cap
              :raycast {:entity-id "target-uuid" :position {:x 1.0 :y 2.0 :z 3.0}}
              nil))
   :command! (fn [cap args _fr] (swap! calls conj [:action cap args]))})

(deftest compiles-against-the-real-vocabulary-test
  (let [ir (run/compile-doc! thunder-bolt-ish)]
    (is (= :thunder-bolt-ish (:id ir)))
    (is (contains? (:entries ir) :default))))

(deftest dispatches-end-to-end-against-a-fake-host-test
  (let [ir (run/compile-doc! thunder-bolt-ish)
        calls (atom [])
        program (run/compile-program ir (fake-host calls))
        input {:tunables {:range 24.0 :damage 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}
        frame (run/dispatch! program :default input)]
    (testing "raycast queried with the resolved capability, not the raw node id"
      (is (= [:query :raycast {:origin {:x 0.0 :y 1.0 :z 0.0} :direction {:x 0.0 :y 0.0 :z 1.0}
                               :distance 24.0 :include-entities? true :include-blocks? true
                               :living-only? true}]
             (first @calls))))
    (testing "the raycast hit a real entity, so the conditional damage action ran"
      (is (= [:action :entity/damage {:target "target-uuid" :amount 8.0}] (second @calls))))
    (testing "cooldown/start ran unconditionally after"
      (is (= [:action :cooldown/start {:name :main :ticks 40}] (nth @calls 2))))
    (testing "vfx! appended a signal to the frame's own outbox"
      (is (= 1 (count (.-vfx frame))))
      (is (= :arc-strike-transient (:effect-id (first (.-vfx frame))))))
    (testing "finish set the frame's result"
      (is (= {:outcome :performed :next-phase nil :end-ability? true} (.-result frame))))))

(def ^:private break-budget-text
  "{:ability :break-budget-test
    :tunables {:energy {:type :double}}
    :do
    [(let remaining $energy)
     (let blocks (target/blocks {:shape {} :limit 128}))
     (each block blocks
       (if (math/lte (:hardness block) remaining)
         [(block/break {:position (:position block)})
          (set! remaining (math/sub remaining (:hardness block)))]
         []))
     (finish {:outcome :performed})]}")

(deftest set-accumulator-actually-decreases-across-real-loop-iterations-test
  (testing "terrain/apply-break-budget's real shape: an each-loop
            accumulator gating which iterations act, proven by ACTUALLY
            DISPATCHING against three candidate blocks (hardness 2.0 each,
            starting energy 5.0) -- must break exactly the first two
            (2.0+2.0=4.0 <= 5.0) and skip the third (would need 6.0), not
            just compile without checking the runtime numbers"
    (let [ir (run/compile-doc! break-budget-text)
          calls (atom [])
          ;; block/break has real declared outputs (:status/:block-id/
          ;; :position, matching the old system) -- :returns :any makes it
          ;; a :query node, dispatched via the host's :query!, not
          ;; :command!.
          host {:query! (fn [cap args _fr]
                         (case cap
                           :block/select [{:hardness 2.0 :position {:x 0.0 :y 0.0 :z 0.0}}
                                         {:hardness 2.0 :position {:x 1.0 :y 0.0 :z 0.0}}
                                         {:hardness 2.0 :position {:x 2.0 :y 0.0 :z 0.0}}]
                           :block/break (do (swap! calls conj [cap args]) nil)))
                :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
          program (run/compile-program ir host)
          input {:tunables {:energy 5.0} :capabilities {}}]
      (run/dispatch! program :default input)
      (is (= 2 (count @calls)) "exactly two blocks broken, not all three")
      (is (= [{:x 0.0 :y 0.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0}]
             (mapv #(:position (second %)) @calls))
          "the FIRST two candidates broke, in order -- proves remaining was
           actually checked and decremented each iteration, not just
           evaluated once against the starting energy"))))

(deftest vec-literal-actually-reaches-the-host-as-an-ordered-vector-test
  (testing "found converting ac's real content for S6 (electron_bomb.edn's
            entity/spawn :add-tags [\"ac_electron_bomb\"]) -- proves a
            :vec-lit argument's RUNTIME value, not just that it compiles"
    (let [ir (run/compile-doc!
              "{:ability :tag-test :activation :instant :tunables {}
                :do [(cooldown/start {:name :main :ticks 1})
                     (vfx! {:effect-id :arc-strike-transient :operation :spawn
                           :start ?caster/eye :tags [:a \"b\" 3]})
                     (finish {:outcome :performed :end-ability? true})]}")
          calls (atom [])
          host {:query! (fn [_cap _args _fr])
                :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
          program (run/compile-program ir host)
          input {:tunables {} :capabilities {:caster/eye {:x 1.0 :y 2.0 :z 3.0}}}
          frame (run/dispatch! program :default input)]
      (is (= [:a "b" 3] (:tags (first (.-vfx frame)))))
      (is (= [[:cooldown/start {:name :main :ticks 1}]] @calls)))))

(deftest damage-does-not-run-when-raycast-misses-test
  (let [ir (run/compile-doc! thunder-bolt-ish)
        calls (atom [])
        host {:query! (fn [_cap _args _fr] (swap! calls conj :query) {:entity-id nil})
              :command! (fn [cap _args _fr] (swap! calls conj [:action cap]))}
        program (run/compile-program ir host)
        input {:tunables {:range 24.0 :damage 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}]
    (run/dispatch! program :default input)
    (is (not (some #(and (vector? %) (= :entity/damage (second %))) @calls))
        "no entity-id on the hit -- the `when` guard must skip combat/damage")
    (is (some #(= [:action :cooldown/start] %) @calls)
        "cooldown/start is unconditional -- it must still run")))
