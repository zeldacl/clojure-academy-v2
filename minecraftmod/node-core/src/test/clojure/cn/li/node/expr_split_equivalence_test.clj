(ns cn.li.node.expr-split-equivalence-test
  "Regression coverage for the JIT-huge-method split in cn.li.node.expr
   (see that namespace's own docstring): evaluate used to be one ~20KB
   case over every opcode, past HotSpot's HugeMethodLimit (8000 bytes) and
   therefore NEVER JIT-compiled. It is now split into one private *-op
   function per opcode namespace, dispatched by (namespace opcode).

   This test asserts every op in cn.li.node.ops/table (the DSL-author-
   visible surface), plus :value/*, :map/get and :random/* (expr-only,
   not exposed as ops), against a fixed expected value -- verified once,
   before this split landed, to match the original single-case
   implementation bit-for-bit against a spread of representative and edge
   inputs. Also covers the extra-ops fallback and the unknown-opcode
   throw, so a future refactor of evaluate's outer dispatch cannot
   silently swallow either path."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [cn.li.node.expr :as expr]
            [cn.li.node.ops :as ops]))

(def ^:private cases
  "[opcode args seed expected]. expected uses = semantics (doubles compare
   exactly here because every input is an exact binary fraction)."
  [[:vec3/add [{:vec3 [1.0 2.0 3.0]} [3.0 4.0 5.0]] 0 {:vec3 [4.0 6.0 8.0]}]
   [:vec3/sub [{:vec3 [1.0 2.0 3.0]} {:vec3 [3.0 4.0 5.0]}] 0 {:vec3 [-2.0 -2.0 -2.0]}]
   [:vec3/scale [{:vec3 [1.0 2.0 3.0]} 2.0] 0 {:vec3 [2.0 4.0 6.0]}]
   [:vec3/length [{:vec3 [3.0 4.0 0.0]}] 0 5.0]
   [:vec3/normalize [{:vec3 [0.0 0.0 0.0]}] 0 {:vec3 [0.0 0.0 0.0]}]
   [:vec3/distance [{:x 0.0 :y 0.0 :z 0.0} {:x 3.0 :y 4.0 :z 0.0}] 0 5.0]
   [:vec3/dot [{:vec3 [1.0 0.0 0.0]} {:vec3 [1.0 0.0 0.0]}] 0 1.0]
   [:vec3/x [{:vec3 [1.0 2.0 3.0]}] 0 1.0]
   [:vec3/y [{:vec3 [1.0 2.0 3.0]}] 0 2.0]
   [:vec3/z [{:vec3 [1.0 2.0 3.0]}] 0 3.0]
   [:vec3/with-z [{:vec3 [1.0 2.0 3.0]} 9.0] 0 {:vec3 [1.0 2.0 9.0]}]
   [:vec3/approach [{:vec3 [0.0 0.0 0.0]} {:vec3 [10.0 0.0 0.0]} 3.0] 0 {:vec3 [3.0 0.0 0.0]}]
   [:vec3/launch [{:vec3 [0.0 1.0 0.0]} 5.0 0.0] 0 {:vec3 [0.0 5.0 0.0]}]
   [:math/add [2.0 3.0] 0 5.0]
   [:math/sub [5.0 2.0] 0 3.0]
   [:math/mul [2.0 3.0] 0 6.0]
   [:math/div [6.0 3.0] 0 2.0]
   [:math/div [6.0 0.0] 0 0.0]
   [:math/min [2.0 3.0] 0 2.0]
   [:math/max [2.0 3.0] 0 3.0]
   [:math/abs [-2.0] 0 2.0]
   [:math/floor [2.7] 0 2.0]
   [:math/floor-long [2.7] 0 2]
   [:math/sqrt [4.0] 0 2.0]
   [:math/pow [2.0 3.0] 0 8.0]
   [:math/sin [0.0] 0 0.0]
   [:math/cos [0.0] 0 1.0]
   [:math/clamp [5.0 0.0 2.0] 0 2.0]
   [:math/lerp [0.0 10.0 0.5] 0 5.0]
   [:math/lt [1.0 2.0] 0 true]
   [:math/lte [2.0 2.0] 0 true]
   [:math/eq [2.0 2.0] 0 true]
   [:math/gte [2.0 2.0] 0 true]
   [:math/gt [3.0 2.0] 0 true]
   [:math/select [true 1 2] 0 1]
   [:math/select [false 1 2] 0 2]
   [:pair/first [[1.0 2.0]] 0 1.0]
   [:pair/second [[1.0 2.0]] 0 2.0]
   [:pair/third [[1.0 2.0 3.0]] 0 3.0]
   [:value/eq [1 1] 0 true]
   [:value/eq [1 2] 0 false]
   [:value/status-id ["jump-boost:1"] 0 :jump-boost]
   [:value/status-max-amplifier ["jump-boost:1"] 0 1]
   [:value/normalize-id ["block.minecraft.iron_block"] 0 "minecraft:iron_block"]
   [:value/normalize-id [nil] 0 nil]
   [:value/normalize-id ["already:namespaced"] 0 "already:namespaced"]
   [:collection/contains? [[1 2 3] 2] 0 true]
   [:collection/contains? [nil 2] 0 false]
   [:collection/concat [[1 2] [3 4]] 0 [1 2 3 4]]
   [:collection/concat [nil [3 4]] 0 [3 4]]
   [:collection/remove [[1 2 3] 2] 0 [1 3]]
   [:collection/first [[1 2 3]] 0 1]
   [:collection/first [[]] 0 nil]
   [:collection/nonempty [[1]] 0 true]
   [:collection/nonempty [[]] 0 false]
   [:map/get [{:a 1 :b 42} :b] 0 42]
   [:map/get [{:a 1} :missing] 0 nil]
   [:bool/and [true false] 0 false]
   [:bool/or [true false] 0 true]
   [:bool/not [true] 0 false]
   [:long/add [2 3] 0 5]
   [:long/sub [5 2] 0 3]
   [:long/mul [2 3] 0 6]
   [:long/min [2 3] 0 2]
   [:long/max [2 3] 0 3]])

(deftest every-fixture-case-matches-test
  (doseq [[opcode args seed expected] cases]
    (is (= expected (expr/evaluate opcode args seed))
        (str opcode " " args))))

(deftest every-known-op-is-covered-by-a-fixture-case-test
  ;; Guards against the fixture table above silently rotting: a new entry
  ;; in ops/table without a matching case here would mean this split's
  ;; equivalence proof no longer covers the real DSL-author-visible surface.
  (let [covered (set (map first cases))
        known (set (keys ops/table))
        uncovered (set/difference known covered)]
    (is (empty? uncovered) (str "ops/table entries with no equivalence fixture: " uncovered))))

(deftest random-ops-are-deterministic-per-seed-test
  (is (= (expr/evaluate :random/uniform [0.0 1.0] 42)
         (expr/evaluate :random/uniform [0.0 1.0] 42)))
  (is (not= (expr/evaluate :random/uniform [0.0 1.0] 42)
            (expr/evaluate :random/uniform [0.0 1.0] (expr/next-seed 42))))
  (is (integer? (expr/evaluate :random/int [0 10] 42)))
  (is (boolean? (expr/evaluate :random/chance [0.5] 42))))

(deftest extra-ops-fallback-still-works-test
  (is (= 10.0
         (expr/evaluate :test/double [5.0] 0
                        {:test/double (fn [args _seed] (* 2.0 (double (first args))))}))))

(deftest unknown-opcode-still-throws-test
  (is (thrown? clojure.lang.ExceptionInfo (expr/evaluate :math/nonexistent [1.0])))
  (testing "an opcode in a KNOWN namespace but not a known case must also
            fall through to extra-ops/throw, not silently return nil"
    (is (thrown? clojure.lang.ExceptionInfo (expr/evaluate :math/does-not-exist [1.0])))))
