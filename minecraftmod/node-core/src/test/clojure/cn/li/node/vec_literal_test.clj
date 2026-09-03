(ns cn.li.node.vec-literal-test
  "Found while converting ac's real ability content for S6
   (electron_bomb.edn's :add-tags [\"ac_electron_bomb\"] and :instance-key
   [:activation :electron-bomb-ray], both node-call argument values): the
   DSL had :map-lit for keyed literal data but nothing for ORDERED literal
   data -- an author-written [...] with non-vec3 (not exactly 3 numbers)
   contents had no expression form at all until :vec-lit. See
   cn.li.node.compile/compile-vec-literal's own docstring for why this is
   map-lit's direct sibling, not a special case."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.pretty :as pretty]
            [cn.li.node.test-fixtures :as fx]))

(deftest vec-literal-of-keywords-compiles-test
  (let [doc (surface/parse
             "{:ability :tag-test :tunables {}
               :do [(let tags [:a :b :c])
                    (cooldown/start {:name :main :ticks 1})
                    (finish {:outcome :performed})]}")
        ir (compile/compile! doc fx/opts)]
    (testing "compiles to a :vec-lit instruction"
      (is (some #(= :vec-lit (:op %)) (mapcat :instrs (:blocks ir)))))))

(deftest vec-literal-with-mixed-static-and-dynamic-values-compiles-test
  (let [doc (surface/parse
             "{:ability :tag-test :tunables {:step {:type :double}}
               :do [(let xs [1.0 $step 3.0])
                    (cooldown/start {:name :main :ticks 1})
                    (finish {:outcome :performed})]}")
        ir (compile/compile! doc fx/opts)]
    (is (some #(= :vec-lit (:op %)) (mapcat :instrs (:blocks ir))))))

(deftest a-real-3-number-vector-still-folds-to-a-vec3-constant-not-a-vec-lit-test
  (testing "vec3-literal? claims the [x y z]-all-numbers shape first (see
            compile-vec-literal's own docstring) -- this must NOT regress
            into treating every real vec3 as a :vec-lit"
    (let [doc (surface/parse
               "{:ability :vec3-test :tunables {}
                 :do [(let v [1.0 2.0 3.0])
                      (cooldown/start {:name :main :ticks 1})
                      (finish {:outcome :performed})]}")
          ir (compile/compile! doc fx/opts)
          instrs (mapcat :instrs (:blocks ir))]
      (is (not (some #(= :vec-lit (:op %)) instrs)))
      (is (not (some #(= :map-lit (:op %)) instrs))))))

(deftest vec-literal-round-trips-test
  (let [text "{:ability :tag-test :tunables {}
              :do [(let tags [:a :b :c])
                   (cooldown/start {:name :main :ticks 1})
                   (finish {:outcome :performed})]}"
        doc1 (surface/parse text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)
        ir2 (compile/compile! doc2 fx/opts)
        doc3 (pretty/unparse ir2)]
    (is (= doc2 doc3))))

(deftest nested-vec-literal-inside-a-node-call-arg-test
  (testing "the actual real-content shape: a vector literal as a node
            call's OWN argument value (e.g. electron_bomb's entity/spawn
            :add-tags [\"ac_electron_bomb\"])"
    (let [doc (surface/parse
               "{:ability :nested :tunables {}
                 :do [(target/raycast {:from ?caster/eye :dir ?caster/eye :distance 10.0
                                       :policy [:a :b]})
                      (finish {:outcome :performed})]}")
          ir (compile/compile! doc fx/opts)]
      (is (some #(= :vec-lit (:op %)) (mapcat :instrs (:blocks ir)))))))
