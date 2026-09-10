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
   ;; direction, speed, pitch-offset-radians -- see cn.li.node.expr's
   ;; matching comment for why this is a new implementation, not a port.
   :vec3/launch    {:params [:vec3 :double :double]   :returns :vec3}
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
   :pair/third     {:params [:any]                    :returns :double}
   :value/eq       {:params [:any :any]               :returns :boolean}
   ;; See cn.li.node.expr's matching comment: body_intensify.edn's (S6)
   ;; "name:max-amplifier" tunable strings, never implemented anywhere
   ;; despite being referenced -- the same never-wired-up-reference class
   ;; :vec3/launch and :vec3/scatter-end already established.
   :value/status-id             {:params [:string] :returns :keyword}
   :value/status-max-amplifier  {:params [:string] :returns :long}
   ;; See cn.li.node.expr's matching comment. S6's mag_movement.edn.
   :value/normalize-id          {:params [:string] :returns :string}
   ;; cn.li.node.expr already implements this (a projectile-type
   ;; allowlist/blocklist check, S6's vec_deviation.edn); exposing it here
   ;; is the same "wire an existing-but-unreachable expr opcode into
   ;; ops/table" fix :math/select needed earlier.
   :collection/contains? {:params [:any :any]         :returns :boolean}
   ;; Same "wire an existing-but-unreachable expr opcode" fix, S6's
   ;; ray_barrage.edn (a single-candidate query result read as an
   ;; optional value, and a nonempty? guard before reading it).
   :collection/first     {:params [:any]               :returns :any}
   :collection/nonempty  {:params [:any]                :returns :boolean}
   ;; S6's scatter_bomb.edn: append a newly-spawned entity id onto the
   ;; session-tracked ball-ids list. Same "wire an existing-but-
   ;; unreachable expr opcode" fix as the others above.
   :collection/concat    {:params [:any :any]           :returns :any}
   ;; S6's electron_missile.edn: drop a spent ball's id out of the
   ;; session-tracked ball-ids list. Same "wire an existing-but-
   ;; unreachable expr opcode" fix as the others above.
   :collection/remove    {:params [:any :any]           :returns :any}
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

;; --- editor palette presentation (:category), attached in schema-export ---
;;
;; A separate lookup rather than a field on every `table` entry: `table`
;; is the hot-path signature lookup (known-op?/signature/invoke all read
;; it), and category is presentation data with nothing to do with
;; compilation -- keeping it out of `table` keeps that map's shape
;; exactly what cn.li.node.compile has always expected. Derived from each
;; op's own namespace, same technique as cn.li.combat.dsl-vocabulary's
;; category-by-namespace, for the same reason (one exhaustive table beats
;; scattering a presentation field across 54 entries).
(def ^:private category-by-namespace
  {"vec3" :vec3 "math" :math "pair" :math "value" :flow
   "collection" :collection "bool" :flow "long" :math})

(defn category-for
  "Editor palette grouping for op-name, :uncategorized if its namespace
   is not in category-by-namespace (schema-export_test.clj asserts this
   never happens for any op actually in `table`)."
  [op-name]
  (get category-by-namespace (namespace op-name) :uncategorized))

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

;; --- primitive fast path for mcmod's emitter (perf plan Phase B) -----------
;;
;; The general :pure path above goes through invoke, an arg VECTOR, and
;; cn.li.node.expr/evaluate's own arg-position dispatch -- every read off an
;; ExecutionFrame's :doubles/:longs bank is already a primitive `aget`, but
;; it gets BOXED the moment it crosses that (fn [frame] ...) IFn.invoke
;; boundary, then boxed again going into the arg vector, then unboxed again
;; inside expr/evaluate's own (double (nth args n)) calls. Measured cost:
;; 4594 ns / 7944 B for an 11-op :math/add chain.
;;
;; node-core cannot import mcmod's ExecutionFrame or any other mcmod type
;; (verifyNodeCoreDependencyDirection) -- but it does not need to: a plain
;; Clojure fn hinted ^double/^long on its args and return is compiled by
;; Clojure itself into an implementation of a clojure.lang.IFn$xyz
;; interface (IFn$DDD for two double args returning double, IFn$OD for one
;; Object arg returning double, etc.) -- clojure.lang is a dependency both
;; node-core and mcmod already have, so this is a real zero-new-edge seam,
;; not a workaround (verified: every shape below actually implements the
;; interface effect-emit casts it to, confirmed with instance? before this
;; landed).
;;
;; Every entry here duplicates one arithmetic expression already defined in
;; cn.li.node.expr (never anything with real logic in it like SplitMix64 or
;; vec3-components, which stay single-sourced there per
;; verifyNodeKernelSingleSource/verifyNoDuplicateUtilities) -- correctness
;; is guaranteed by cn.li.mcmod.runtime.effect-emit's own differential test,
;; which dispatches the SAME real IR through the generic path and this
;; specialized path and asserts byte-for-byte agreement, not by this
;; namespace's own tests alone.
;;
;; :arg-banks/:dst-bank are cn.li.node.types/bank values -- effect-emit
;; matches them against an IR instruction's actual [kind bank slot]
;; register references before ever using :fn, and falls back to the
;; generic invoke path on any mismatch (an op present here compiled
;; against unexpected banks, or simply absent from this table).
(def prim-table
  "op-name -> {:arg-banks [bank...] :dst-bank bank :fn primitive-fn}.
   Only :math/* (minus :select, whose :any args have no fixed bank) and
   :long/* are covered -- vec3/collection/value/pair/map ops read and
   write the :objects bank, which cn.li.mcmod.runtime.effect-emit's
   EXISTING generic reader already returns unboxed (an Object array read
   is not a primitive-boxing operation), so specializing them would not
   remove any boxing, only an arg-vector allocation -- not worth the
   combinatorial surface this table would otherwise need. :bool/* is the
   same story for a different reason: Clojure has no primitive `boolean`
   letter in its IFn family (confirmed: this repo's own clojure.jar has
   every D/L/O combination up to 5 args, never a B), so a bool/and|or|not
   specialization could only save the arg-vector allocation, not any
   unboxing -- Boolean/TRUE and Boolean/FALSE are cached singletons, so
   there is nothing to unbox in the first place."
  {:math/add {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (+ a b))}
   :math/sub {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (- a b))}
   :math/mul {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (* a b))}
   :math/div {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (if (zero? b) 0.0 (/ a b)))}
   :math/min {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (min a b))}
   :math/max {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (max a b))}
   :math/pow {:arg-banks [:doubles :doubles] :dst-bank :doubles
              :fn (fn ^double [^double a ^double b] (Math/pow a b))}
   :math/abs {:arg-banks [:doubles] :dst-bank :doubles
              :fn (fn ^double [^double a] (Math/abs a))}
   :math/floor {:arg-banks [:doubles] :dst-bank :doubles
                :fn (fn ^double [^double a] (Math/floor a))}
   :math/sqrt {:arg-banks [:doubles] :dst-bank :doubles
               :fn (fn ^double [^double a] (Math/sqrt a))}
   :math/sin {:arg-banks [:doubles] :dst-bank :doubles
              :fn (fn ^double [^double a] (Math/sin a))}
   :math/cos {:arg-banks [:doubles] :dst-bank :doubles
              :fn (fn ^double [^double a] (Math/cos a))}
   ;; See cn.li.node.expr's matching comment: the one legitimate
   ;; double->long narrowing case.
   :math/floor-long {:arg-banks [:doubles] :dst-bank :longs
                      :fn (fn ^long [^double a] (long (Math/floor a)))}
   :math/clamp {:arg-banks [:doubles :doubles :doubles] :dst-bank :doubles
                :fn (fn ^double [^double v ^double lo ^double hi] (max lo (min hi v)))}
   :math/lerp {:arg-banks [:doubles :doubles :doubles] :dst-bank :doubles
               :fn (fn ^double [^double lo ^double hi ^double t] (+ lo (* t (- hi lo))))}
   :math/lt {:arg-banks [:doubles :doubles] :dst-bank :booleans
             :fn (fn [^double a ^double b] (< a b))}
   :math/lte {:arg-banks [:doubles :doubles] :dst-bank :booleans
              :fn (fn [^double a ^double b] (<= a b))}
   :math/eq {:arg-banks [:doubles :doubles] :dst-bank :booleans
             :fn (fn [^double a ^double b] (= a b))}
   :math/gte {:arg-banks [:doubles :doubles] :dst-bank :booleans
              :fn (fn [^double a ^double b] (>= a b))}
   :math/gt {:arg-banks [:doubles :doubles] :dst-bank :booleans
             :fn (fn [^double a ^double b] (> a b))}
   :long/add {:arg-banks [:longs :longs] :dst-bank :longs
              :fn (fn ^long [^long a ^long b] (+ a b))}
   :long/sub {:arg-banks [:longs :longs] :dst-bank :longs
              :fn (fn ^long [^long a ^long b] (- a b))}
   :long/mul {:arg-banks [:longs :longs] :dst-bank :longs
              :fn (fn ^long [^long a ^long b] (* a b))}
   :long/min {:arg-banks [:longs :longs] :dst-bank :longs
              :fn (fn ^long [^long a ^long b] (min a b))}
   :long/max {:arg-banks [:longs :longs] :dst-bank :longs
              :fn (fn ^long [^long a ^long b] (max a b))}})
