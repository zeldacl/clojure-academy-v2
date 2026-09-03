(ns cn.li.node.if-test
  "Two-armed if, and the regression this surfaced: compile-when/compile-each
   unconditionally appended a :jump/increment after their body, even when
   that body already ended in `finish` -- corrupting the IR (a non-terminal
   :finish mid-block) the moment any when/each body's last statement
   terminates. Found while auditing real ability content for the S6
   migration (thunder_bolt-style effective/ineffective branches, insufficient-
   resource early exits) before it could bite mid-conversion; none of the
   existing S1 fixtures happened to end a when/each body in finish."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.ir :as ir]
            [cn.li.node.pretty :as pretty]
            [cn.li.node.test-fixtures :as fx]))

(deftest if-both-arms-compile-and-run-test
  (let [doc (surface/parse
             "{:ability :branchy :tunables {:range {:type :double}}
               :do [(let hit (target/raycast {:from ?caster/eye :dir ?caster/eye :distance $range}))
                    (if (:entity-id hit)
                      [(cooldown/start {:name :hit :ticks 1}) (finish {:outcome :performed})]
                      [(cooldown/start {:name :miss :ticks 1}) (finish {:outcome :ineffective})])]}")
        ir (compile/compile! doc fx/opts)]
    (is (map? (ir/validate! ir)))
    (testing "both arms independently reach their own finish"
      (let [instrs (mapcat :instrs (:blocks ir))
            finishes (filter #(= :finish (:op %)) instrs)]
        (is (= 2 (count finishes)))
        (is (= #{:performed :ineffective} (set (map :outcome finishes))))))))

(deftest when-body-ending-in-finish-does-not-corrupt-the-block-test
  (testing "the exact regression: a when-body whose LAST statement is
            `finish` must not have anything appended after it"
    (let [doc (surface/parse
               "{:ability :early-exit :tunables {}
                 :do [(when true
                        (finish {:outcome :insufficient-resource}))
                      (cooldown/start {:name :main :ticks 1})
                      (finish {:outcome :performed})]}")
          ir (compile/compile! doc fx/opts)]
      (is (map? (ir/validate! ir))
          "ir/validate! itself checks a :finish is always block-terminal; this
           would have thrown before the fix"))))

(deftest each-body-ending-in-finish-does-not-corrupt-the-block-test
  (let [doc (surface/parse
             "{:ability :early-exit-loop :tunables {}
               :do [(let xs (target/entities {:center [0.0 0.0 0.0] :radius 1.0}))
                    (each t xs
                      (when (:entity-id t)
                        (finish {:outcome :performed})))
                    (finish {:outcome :ended})]}")
        ir (compile/compile! doc fx/opts)]
    (is (map? (ir/validate! ir)))))

(deftest if-round-trips-test
  (let [text "{:ability :branchy :tunables {:range {:type :double}}
              :do [(let hit (target/raycast {:from ?caster/eye :dir ?caster/eye :distance $range}))
                   (if (:entity-id hit)
                     [(cooldown/start {:name :hit :ticks 1}) (finish {:outcome :performed})]
                     [(finish {:outcome :ineffective})])]}"
        doc1 (surface/parse text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)
        ir2 (compile/compile! doc2 fx/opts)
        doc3 (pretty/unparse ir2)]
    (is (= doc2 doc3))))

(deftest if-with-fall-through-both-arms-reach-shared-code-after-test
  (testing "neither arm terminates -- a real continue-id is allocated, and
            the statement after the `if` (cooldown/start :shared) must be
            reachable from BOTH arms, appearing exactly once in the IR"
    (let [doc (surface/parse
               "{:ability :both-continue :tunables {}
                 :do [(if true
                        [(cooldown/start {:name :a :ticks 1})]
                        [(cooldown/start {:name :b :ticks 1})])
                      (cooldown/start {:name :shared :ticks 2})
                      (finish {:outcome :performed})]}")
          ir (compile/compile! doc fx/opts)
          instrs (mapcat :instrs (:blocks ir))]
      (is (map? (ir/validate! ir)))
      ;; :name's arg register is a :const [:objects bank slot] holding the
      ;; keyword literal -- resolve it back to confirm exactly one physical
      ;; :cooldown/start instruction was emitted for :shared, not one per arm.
      (let [cooldown-instrs (filter #(and (= :action (:op %)) (= :cooldown/start (:node %))) instrs)
            names (map (fn [i] (get-in ir [:constants :objects (nth (get-in i [:args :name]) 2)])) cooldown-instrs)]
        (is (= [:a :b :shared] (sort-by str names)))))))
