(ns cn.li.node.digest
  "Deterministic, cross-process content hashing for catalog/effect data.

   Lifted from vfx-core's effect_schema.clj, which already had this right
   (SHA-256 over a canonicalized, deterministically-ordered structure) --
   unlike ac's final_catalog.clj, which fed the same kind of value into
   clojure.core/hash, a JVM-LOCAL hash whose result is meaningless across
   two different JVM processes (two players' clients, or a client and a
   server) even though it was being used for exactly that: a network/build
   content-identity check.

   canonical sorts map entries by the printed form of their key rather than
   by natural key order: pr-str always produces mutually comparable strings,
   so this stays correct for catalog data with non-keyword or mixed-type
   keys, not just the keyword-only maps effect-schema happened to hash.")

(defn canonical
  "`value` with every map's entries in a deterministic order and every set
   turned into a sorted vector, so two structurally-equal values always
   print identically regardless of hash-order or construction history."
  [value]
  (cond
    (map? value)
    (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map (fn [[k v]] [k (canonical v)]))
          value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- sha256-hex [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes s "UTF-8"))]
    (format "%064x" (java.math.BigInteger. 1 bytes))))

(defn content-hash
  "A deterministic SHA-256 hex digest of `value`, stable across JVM
   processes and runs -- safe to compare over the network or across a
   build, unlike clojure.core/hash."
  [value]
  (sha256-hex (pr-str (canonical value))))
