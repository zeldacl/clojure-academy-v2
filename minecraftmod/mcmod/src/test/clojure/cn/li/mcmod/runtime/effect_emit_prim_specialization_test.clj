(ns cn.li.mcmod.runtime.effect-emit-prim-specialization-test
  "Correctness proof for the primitive :pure fast path (perf plan Phase B):
   cn.li.mcmod.runtime.effect-emit/compile-pure-specialized. mcmod cannot
   depend on node-core even in test scope (see effect-emit-test's own
   docstring), so prim-ops fixtures here are hand-built maps matching
   cn.li.node.ops/prim-table's shape, not the real table -- the real
   table's own semantics are proven equal to cn.li.node.expr separately by
   node-core's prim-table-equivalence-test. What THIS file proves is the
   MECHANISM: given any compatible prim-ops table, dispatching a :pure
   instruction through the specialized path produces the IDENTICAL
   ExecutionFrame as dispatching the same instruction through the generic
   invoke-op path -- for every specialized shape, and a safe fallback to
   the generic path both when the op is absent from prim-ops and when its
   declared banks do not match the instruction's actual register banks."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.runtime.effect-emit :as emit])
  (:import [cn.li.mcmod.runtime.effect ExecutionFrame]))

(defn- reg [bank slot] [:reg bank slot])
(defn- const [bank slot] [:const bank slot])

;; A generic invoke-op standing in for cn.li.node.ops/invoke: covers every
;; op name the fixtures below use, so the GENERIC path (prim-ops absent or
;; non-matching) is always computable and directly comparable against the
;; specialized path's result.
(def ^:private invoke-op
  (fn [op args]
    (case op
      :add (double (+ (double (nth args 0)) (double (nth args 1))))
      :neg-abs (double (Math/abs (double (nth args 0))))
      :clampish (let [v (double (nth args 0)) lo (double (nth args 1)) hi (double (nth args 2))]
                  (max lo (min hi v)))
      :gt (boolean (> (double (nth args 0)) (double (nth args 1))))
      :long-add (long (+ (long (nth args 0)) (long (nth args 1))))
      :floorish (long (Math/floor (double (nth args 0))))
      :fake-op-3args (double (+ (double (nth args 0)) (double (nth args 1)) (double (nth args 2))))
      (throw (ex-info "unknown test op" {:op op})))))

;; Matching cn.li.node.ops/prim-table entries, one per specialized shape.
(def ^:private prim-ops
  {:add {:arg-banks [:doubles :doubles] :dst-bank :doubles
         :fn (fn ^double [^double a ^double b] (+ a b))}
   :neg-abs {:arg-banks [:doubles] :dst-bank :doubles
             :fn (fn ^double [^double a] (Math/abs a))}
   :clampish {:arg-banks [:doubles :doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double v ^double lo ^double hi] (max lo (min hi v)))}
   :gt {:arg-banks [:doubles :doubles] :dst-bank :booleans
        :fn (fn [^double a ^double b] (> a b))}
   :long-add {:arg-banks [:longs :longs] :dst-bank :longs
              :fn (fn ^long [^long a ^long b] (+ a b))}
   :floorish {:arg-banks [:doubles] :dst-bank :longs
              :fn (fn ^long [^double a] (long (Math/floor a)))}})

(defn- run-program [ir env]
  (let [program (emit/compile-program ir env)
        frame (emit/new-frame program (:input env))]
    (emit/dispatch! program :default frame)
    frame))

(defn- frame-state
  "Everything about a frame that a :pure specialization must leave
   byte-for-byte identical to the generic path: every register bank's
   full contents (not just the one slot the instruction under test
   wrote), plus outcome and every accumulator."
  [^ExecutionFrame fr]
  {:doubles (vec (.-doubles fr)) :longs (vec (.-longs fr)) :booleans (vec (.-booleans fr))
   :result (.-result fr) :actions (into [] (.-actions fr)) :vfx (into [] (.-vfx fr))
   :events (into [] (.-events fr)) :state-writes (into [] (.-stateWrites fr))})

(defn- compare-specialized-vs-generic!
  "Runs `ir` through the specialized path (env includes prim-ops) and the
   generic path (env omits it, forcing every :pure instruction through
   invoke-op) and asserts identical resulting frame state."
  [ir]
  (let [specialized (frame-state (run-program ir {:invoke-op invoke-op :prim-ops prim-ops}))
        generic (frame-state (run-program ir {:invoke-op invoke-op}))]
    (is (= generic specialized)
        "specialized :pure dispatch must produce the identical frame as the generic path")
    specialized))

(defn- finish-block [instrs]
  [{:id 0 :instrs (conj (vec instrs)
                        {:op :finish :nid "fin" :outcome :performed :next-phase nil :end-ability? true})}])

(deftest dd->d-shape-test
  (testing "2 double consts -> double register"
    (let [ir {:ir/version 1 :id :t :registers {:doubles 1 :longs 0 :booleans 0 :objects 0}
              :constants {:doubles [2.0 3.0] :longs [] :booleans [] :objects []}
              :entries {:default 0}
              :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :doubles 0) :fn :add
                                      :args [(const :doubles 0) (const :doubles 1)]}])}
          state (compare-specialized-vs-generic! ir)]
      (is (= 5.0 (nth (:doubles state) 0))))))

(deftest d->d-shape-test
  (let [ir {:ir/version 1 :id :t :registers {:doubles 1 :longs 0 :booleans 0 :objects 0}
            :constants {:doubles [-7.5] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :doubles 0) :fn :neg-abs
                                    :args [(const :doubles 0)]}])}
        state (compare-specialized-vs-generic! ir)]
    (is (= 7.5 (nth (:doubles state) 0)))))

(deftest ddd->d-shape-test
  (let [ir {:ir/version 1 :id :t :registers {:doubles 1 :longs 0 :booleans 0 :objects 0}
            :constants {:doubles [5.0 0.0 2.0] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :doubles 0) :fn :clampish
                                    :args [(const :doubles 0) (const :doubles 1) (const :doubles 2)]}])}
        state (compare-specialized-vs-generic! ir)]
    (is (= 2.0 (nth (:doubles state) 0)))))

(deftest dd->b-shape-test
  (testing "boolean-bank destination, boxed via the safety unboxing cast"
    (let [ir {:ir/version 1 :id :t :registers {:doubles 0 :longs 0 :booleans 1 :objects 0}
              :constants {:doubles [3.0 2.0] :longs [] :booleans [] :objects []}
              :entries {:default 0}
              :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :booleans 0) :fn :gt
                                      :args [(const :doubles 0) (const :doubles 1)]}])}
          state (compare-specialized-vs-generic! ir)]
      (is (= true (nth (:booleans state) 0))))))

(deftest ll->l-shape-test
  (let [ir {:ir/version 1 :id :t :registers {:doubles 0 :longs 1 :booleans 0 :objects 0}
            :constants {:doubles [] :longs [7 8] :booleans [] :objects []}
            :entries {:default 0}
            :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :longs 0) :fn :long-add
                                    :args [(const :longs 0) (const :longs 1)]}])}
        state (compare-specialized-vs-generic! ir)]
    (is (= 15 (nth (:longs state) 0)))))

(deftest d->l-shape-test
  (let [ir {:ir/version 1 :id :t :registers {:doubles 0 :longs 1 :booleans 0 :objects 0}
            :constants {:doubles [-2.3] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :longs 0) :fn :floorish
                                    :args [(const :doubles 0)]}])}
        state (compare-specialized-vs-generic! ir)]
    (is (= -3 (nth (:longs state) 0)) "floor(-2.3) rounds toward negative infinity, not truncates")))

(deftest chained-register-reads-across-instructions-test
  (testing "an 11-op chain: every op after the first reads a REGISTER a
            prior specialized instruction just wrote, not a constant --
            the real shape production content takes (each math node
            feeding the next) and the one bug class a wrong slot/bank
            assumption in the specialization would actually surface as."
    (let [step (fn [i] {:op :pure :nid (str "n" i) :dst (reg :doubles 1) :fn :add
                        :args [(reg :doubles (if (zero? i) 0 1)) (const :doubles 1)]})
          instrs (into [{:op :copy :nid "seed" :dst (reg :doubles 0) :src (const :doubles 0)}]
                       (map step (range 11)))
          ir {:ir/version 1 :id :t :registers {:doubles 2 :longs 0 :booleans 0 :objects 0}
              :constants {:doubles [2.5 2.5] :longs [] :booleans [] :objects []}
              :entries {:default 0}
              :blocks (finish-block instrs)}
          state (compare-specialized-vs-generic! ir)]
      ;; seed = 2.5, then reg1 = (reg0 or reg1) + 2.5 eleven times:
      ;; 2.5 + 11*2.5 = 30.0
      (is (= 30.0 (nth (:doubles state) 1))))))

(deftest unknown-op-falls-back-to-generic-path-test
  (testing "an op present in the IR but ABSENT from prim-ops must still
            dispatch correctly through invoke-op, not throw or silently
            no-op"
    (let [ir {:ir/version 1 :id :t :registers {:doubles 1 :longs 0 :booleans 0 :objects 0}
              :constants {:doubles [1.0 2.0 3.0] :longs [] :booleans [] :objects []}
              :entries {:default 0}
              :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :doubles 0) :fn :fake-op-3args
                                      :args [(const :doubles 0) (const :doubles 1) (const :doubles 2)]}])}
          frame (run-program ir {:invoke-op invoke-op :prim-ops prim-ops})]
      (is (= 6.0 (aget ^doubles (.-doubles frame) 0))))))

(deftest bank-mismatch-falls-back-to-generic-path-test
  (testing "prim-ops declares :add as [:doubles :doubles]->:doubles, but
            THIS instruction's dst is :objects (e.g. a hypothetical
            :any-typed call site) -- must not attempt the specialized
            aset (which would ClassCastException against the wrong array
            or silently corrupt an unrelated bank), must fall back"
    (let [ir {:ir/version 1 :id :t :registers {:doubles 0 :longs 0 :booleans 0 :objects 1}
              :constants {:doubles [2.0 3.0] :longs [] :booleans [] :objects []}
              :entries {:default 0}
              :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :objects 0) :fn :add
                                      :args [(const :doubles 0) (const :doubles 1)]}])}
          frame (run-program ir {:invoke-op invoke-op :prim-ops prim-ops})]
      (is (= 5.0 (aget ^objects (.-objects frame) 0))))))

(deftest nil-prim-ops-behaves-exactly-like-absent-test
  (let [ir {:ir/version 1 :id :t :registers {:doubles 1 :longs 0 :booleans 0 :objects 0}
            :constants {:doubles [2.0 3.0] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks (finish-block [{:op :pure :nid "n1" :dst (reg :doubles 0) :fn :add
                                    :args [(const :doubles 0) (const :doubles 1)]}])}
        with-nil (run-program ir {:invoke-op invoke-op :prim-ops nil})
        absent (run-program ir {:invoke-op invoke-op})]
    (is (= (vec (.-doubles with-nil)) (vec (.-doubles absent))))))
