(ns cn.li.mcmod.runtime.seeded-rng
  "Deterministic SplitMix64-style RNG; no global rand in effect execution." )

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn ^long next-long [^long state]
  (let [z (unchecked-add state -7046029254386353131)
        z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 30))
                              -4658895280553007687)
        z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 27))
                              -7723592293110705685)]
    (bit-xor z (unsigned-bit-shift-right z 31))))

;; The SplitMix64 mixing step is duplicated below (in `unit-double`,
;; `uniform`, `bounded-int`) instead of calling `next-long`. A cross-fn call
;; from inside a Var-boxed dispatch site -- this namespace is reached
;; through vm.clj's own ^clojure.lang.IFn indirection, forced there for the
;; same reason (see vm.clj's comment above `expr-add`) -- can resolve to a
;; primitive `invokePrim` signature `next-long`'s ^long-hinted
;; implementation doesn't provide, throwing AbstractMethodError; a
;; local-Var indirection to dodge it in turn hit a compiler limitation
;; (`UnsupportedOperationException compiling let*`) mixing a ^long-hinted
;; local with :unchecked-math. Duplicating these four lines removes the
;; cross-fn call site entirely instead of chasing the mismatch further.
(defn ^double unit-double [^long state]
  (let [z1 (unchecked-add state -7046029254386353131)
        z2 (unchecked-multiply (bit-xor z1 (unsigned-bit-shift-right z1 30))
                               -4658895280553007687)
        z3 (unchecked-multiply (bit-xor z2 (unsigned-bit-shift-right z2 27))
                               -7723592293110705685)
        next (bit-xor z3 (unsigned-bit-shift-right z3 31))]
    (/ (double (bit-and (unsigned-bit-shift-right next 11)
                      0x1fffffffffffff))
       9007199254740992.0)))

(defn ^double uniform [^long state ^double lo ^double hi]
  (let [z1 (unchecked-add state -7046029254386353131)
        z2 (unchecked-multiply (bit-xor z1 (unsigned-bit-shift-right z1 30))
                               -4658895280553007687)
        z3 (unchecked-multiply (bit-xor z2 (unsigned-bit-shift-right z2 27))
                               -7723592293110705685)
        next (bit-xor z3 (unsigned-bit-shift-right z3 31))
        unit (/ (double (bit-and (unsigned-bit-shift-right next 11)
                                 0x1fffffffffffff))
                9007199254740992.0)]
    (+ lo (* (- hi lo) unit))))

(defn ^long bounded-int [^long state ^long lo ^long hi]
  (if (<= hi lo)
    lo
    (let [z1 (unchecked-add state -7046029254386353131)
          z2 (unchecked-multiply (bit-xor z1 (unsigned-bit-shift-right z1 30))
                                 -4658895280553007687)
          z3 (unchecked-multiply (bit-xor z2 (unsigned-bit-shift-right z2 27))
                                 -7723592293110705685)
          next (bit-xor z3 (unsigned-bit-shift-right z3 31))
          unit (/ (double (bit-and (unsigned-bit-shift-right next 11)
                                   0x1fffffffffffff))
                  9007199254740992.0)]
      (+ lo (long (* unit (double (inc (- hi lo)))))))))
