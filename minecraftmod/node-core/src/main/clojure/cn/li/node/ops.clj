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
   :value/eq       {:params [:any :any]               :returns :boolean}
   :bool/and       {:params [:boolean :boolean]       :returns :boolean}
   :bool/or        {:params [:boolean :boolean]       :returns :boolean}
   :bool/not       {:params [:boolean]                :returns :boolean}})

(defn known-op? [op-name] (contains? table op-name))

(defn signature
  "{:params [...] :returns t} for op-name, or nil if unknown."
  [op-name]
  (get table op-name))

(defn invoke
  "Call op-name's underlying pure function against already-resolved args."
  [op-name args]
  (when-not (known-op? op-name)
    (throw (ex-info "unknown pure op" {:op op-name})))
  (expr/evaluate op-name (vec args)))
