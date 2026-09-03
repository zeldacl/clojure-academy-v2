(ns cn.li.node.ops
  "Pure expression op table for the surface-DSL compiler (cn.li.node.compile):
   name, parameter types, and return type. Every op name here IS an
   cn.li.node.expr opcode -- invoke delegates straight to expr/evaluate
   instead of re-implementing the math, so the SplitMix64 mixing constants
   and the vec3-components shape dispatch stay defined in exactly one place
   each (see verifyNodeKernelSingleSource / verifyNoDuplicateUtilities in the
   root build.gradle -- both gates fail the build on a second definition
   site anywhere in the repo).

   Only ops with NO dependency on a runtime seed are listed here: every op
   in this table is a pure function of its argument values alone, which is
   what lets cn.li.node.compile treat a single-consumer result as inlinable
   when pretty-printing (cn.li.node.pretty) and what lets cn.li.node.cost
   treat these instructions as zero (host-)cost. Seeded ops (:random/*) read
   the ExecutionFrame's RNG cursor at runtime and are registered on the
   emitter side (cn.li.mcmod.runtime.effect.emit), not here."
  (:require [cn.li.node.expr :as expr]))

(def table
  "op-name -> {:params [type...] :returns type}. :params is positional and
   fixed-arity -- every op below takes exactly as many arguments as
   cn.li.node.expr/evaluate's matching opcode does."
  {:vec3/add       {:params [:vec3 :vec3]             :returns :vec3}
   :vec3/sub       {:params [:vec3 :vec3]             :returns :vec3}
   :vec3/scale     {:params [:vec3 :double]           :returns :vec3}
   :vec3/length    {:params [:vec3]                   :returns :double}
   :vec3/normalize {:params [:vec3]                   :returns :vec3}
   :vec3/distance  {:params [:vec3 :vec3]             :returns :double}
   :vec3/dot       {:params [:vec3 :vec3]             :returns :double}
   :vec3/x         {:params [:vec3]                   :returns :double}
   :vec3/y         {:params [:vec3]                   :returns :double}
   :vec3/z         {:params [:vec3]                   :returns :double}
   :vec3/with-z    {:params [:vec3 :double]           :returns :vec3}
   :vec3/approach  {:params [:vec3 :vec3 :double]     :returns :vec3}
   :math/add       {:params [:double :double]         :returns :double}
   :math/sub       {:params [:double :double]         :returns :double}
   :math/mul       {:params [:double :double]         :returns :double}
   :math/div       {:params [:double :double]         :returns :double}
   :math/min       {:params [:double :double]         :returns :double}
   :math/max       {:params [:double :double]         :returns :double}
   :math/abs       {:params [:double]                 :returns :double}
   :math/floor     {:params [:double]                 :returns :double}
   ;; See cn.li.node.expr's matching comment: the one legitimate
   ;; double->long narrowing case (a curve-evaluated tick count), named
   ;; for exactly that use, not a general unsafe cast.
   :math/floor-long {:params [:double]                :returns :long}
   :math/sqrt      {:params [:double]                 :returns :double}
   :math/pow       {:params [:double :double]         :returns :double}
   :math/sin       {:params [:double]                 :returns :double}
   :math/cos       {:params [:double]                 :returns :double}
   :math/clamp     {:params [:double :double :double] :returns :double}
   :math/lerp      {:params [:double :double :double] :returns :double}
   :math/lt        {:params [:double :double]         :returns :boolean}
   :math/lte       {:params [:double :double]         :returns :boolean}
   :math/eq        {:params [:double :double]         :returns :boolean}
   :math/gte       {:params [:double :double]         :returns :boolean}
   :math/gt        {:params [:double :double]         :returns :boolean}
   ;; Generic ternary: (math/select cond then else). :any/:any because
   ;; then/else can be any matching type (a :long tick count, a :vec3, an
   ;; :entity-ref, ...) -- cn.li.node.expr/evaluate's own impl is already
   ;; type-agnostic (returns whichever arg the boolean picked), this just
   ;; exposes it to DSL authors the same way every other op here does.
   :math/select    {:params [:boolean :any :any]      :returns :any}
   ;; See cn.li.node.expr's matching comment: a {:curve :pair} tunable's
   ;; runtime value is an opaque 2-element vector, :any at compile time.
   :pair/first     {:params [:any]                    :returns :double}
   :pair/second    {:params [:any]                    :returns :double}
   :value/eq       {:params [:any :any]               :returns :boolean}
   :bool/and       {:params [:boolean :boolean]       :returns :boolean}
   :bool/or        {:params [:boolean :boolean]       :returns :boolean}
   :bool/not       {:params [:boolean]                :returns :boolean}
   ;; :long/* -- see cn.li.node.expr's matching comment for why :math/*
   ;; (hard-coded double) cannot serve :long-typed tick-count arithmetic.
   :long/add       {:params [:long :long]             :returns :long}
   :long/sub       {:params [:long :long]             :returns :long}
   :long/mul       {:params [:long :long]             :returns :long}
   :long/min       {:params [:long :long]             :returns :long}
   :long/max       {:params [:long :long]             :returns :long}})

(defn known-op? [op-name] (contains? table op-name))

(defn signature
  "{:params [...] :returns t} for op-name, or nil if unknown."
  [op-name]
  (get table op-name))

;; cn.li.node.compile/compile-each emits :pure instructions naming these
;; three ops directly (see that function's own docstring), but they are
;; deliberately absent from `table`/known-op?/signature: compile-pure-call
;; only ever recognizes a DSL-authored (op-name ...) call via known-op?,
;; so a graph author can never reference them -- they exist purely to
;; desugar `each`. invoke, unlike known-op?, is the SHARED execution
;; entry point cn.li.mcmod.runtime.effect-emit calls for every :pure
;; instruction regardless of origin, so it must still know how to run
;; them; this is the one intentional gap between "known to authors" and
;; "known to invoke" in this namespace. Caught by run_test.clj's
;; set-accumulator test failing at DISPATCH time (not compile time) --
;; no `each`-containing program had ever actually been executed
;; end-to-end before that test, since every earlier fixture happened to
;; avoid loops.
(defn- synthetic-invoke [op-name args]
  (case op-name
    :collection/count (long (count (first args)))
    :collection/nth (nth (first args) (long (second args)))
    :long/inc (inc (long (first args)))
    ::not-synthetic))

(defn invoke
  "Call op-name's underlying pure function against already-resolved args."
  [op-name args]
  (let [synthetic (synthetic-invoke op-name args)]
    (if-not (= ::not-synthetic synthetic)
      synthetic
      (do (when-not (known-op? op-name)
            (throw (ex-info "unknown pure op" {:op op-name})))
          (expr/evaluate op-name (vec args))))))
