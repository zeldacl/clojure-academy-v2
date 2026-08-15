(ns cn.li.vfx.random
  "Deterministic random helpers shared by neutral VFX descriptors.

   Seeds are derived from stable printed values rather than JVM identity or
   process-global RNG state, so replaying the same events produces the same
   geometry on every platform and client run.")

(defn seed
  [& values]
  (reduce (fn [^long acc value]
            (unchecked-add (unchecked-multiply acc 31)
                           (long (.hashCode (pr-str value)))))
          17
          values))

(defn non-negative-seed
  [& values]
  (bit-and (long (apply seed values)) 0x7fffffffffffffff))

(defn int-at
  "Return a deterministic integer in [0,bound), or 0 for non-positive bounds."
  [seed-value bound]
  (let [bound (long bound)]
    (if (pos? bound)
      (int (mod (non-negative-seed seed-value) bound))
      0)))
