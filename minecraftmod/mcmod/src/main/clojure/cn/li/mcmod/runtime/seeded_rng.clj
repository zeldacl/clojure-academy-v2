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

(defn ^double unit-double [^long state]
  (let [^long next (next-long state)]
    (/ (double (bit-and (unsigned-bit-shift-right next 11)
                      0x1fffffffffffff))
       9007199254740992.0)))

(defn ^double uniform [^long state ^double lo ^double hi]
  (+ lo (* (- hi lo) (double (unit-double state)))))

(defn ^long bounded-int [^long state ^long lo ^long hi]
  (if (<= hi lo)
    lo
    (+ lo (long (* (double (unit-double state)) (double (inc (- hi lo))))))))
