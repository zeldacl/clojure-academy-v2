(ns cn.li.node.rng
  "The one seeded RNG for the whole repo -- SplitMix64-based, deterministic
   per (seed, call-index). A thin facade over cn.li.node.expr's RNG
   primitives for host-side kernels (block-break budgets, terrain scatter,
   ...) that need a random stream but aren't evaluating EDN expressions, so
   they depend on this narrow surface instead of the whole opcode evaluator.

   Before this namespace existed, mcmod/runtime/seeded-rng.clj carried a
   second, differently-mixed SplitMix64 variant: its next-long advanced AND
   mixed in one step (state' = mix(state + gamma)), while cn.li.node.expr
   only mixes on read (unit-double(seed) = mix(seed), no implicit advance).
   The same :seed value fed to :random/chance and to a kernel RNG call
   produced different draws from the same logical position in the stream.
   This facade is the fix: every consumer, expression or kernel, now reads
   through the same next-seed/unit-double pair.

   Calling convention: advance with next-seed before every independent draw,
   then read the CURRENT seed with unit-double/uniform/bounded-int without
   advancing again -- reading twice from the same seed without an
   intervening next-seed returns the same value both times."
  (:require [cn.li.node.expr :as expr]))

;; Hint the arg vector, not the defn symbol -- matching cn.li.node.expr's own
;; style below. The symbol-hint form (`defn ^long name [...]`) generated a
;; wrapper whose compiled class didn't reliably implement the primitive IFn
;; interface a caller's own compiled call site expected (AbstractMethodError:
;; "does not define or inherit an implementation of ... IFn$LO.invokePrim",
;; thrown at runtime, not caught at compile time -- see the primitive-hinted-
;; fn memory note for how this class of bug surfaces and gets diagnosed).
(defn next-seed ^long [^long seed] (expr/next-seed seed))
(defn unit-double ^double [^long seed] (expr/unit-double seed))
(defn uniform ^double [^long seed ^double lo ^double hi] (expr/uniform seed lo hi))
(defn bounded-int ^long [^long seed ^long lo ^long hi] (expr/bounded-int seed lo hi))
