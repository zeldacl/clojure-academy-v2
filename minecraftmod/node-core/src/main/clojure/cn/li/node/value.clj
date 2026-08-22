(ns cn.li.node.value
  "Generic resolution of a node's non-structural fields. There are exactly
   two deferred value forms in the language:

     {:ref [:local name & path]}   -- read a previously bound local
     {:expr op :args [...]}        -- evaluate an expression (cn.li.node.expr)

   Everything else is a literal (numbers, strings, vec3/color maps, nested
   plain maps/vectors of the above). The old language had five deferred
   forms ({:expr}/{:ref}/{:from}/{:tunable}/{:invariant}); {:from}/
   {:tunable}/{:invariant} are gone -- environment reads are now ordinary
   source-node outputs bound to locals like anything else (NODE_LANGUAGE.md
   section 5), so this resolver never needs a domain-specific case."
  (:require [cn.li.node.expr :as expr]))

(defn resolve-value
  "Deep-resolve `value` against `locals` (a map of local name -> value) and
   `seed` (threaded into cn.li.node.expr/evaluate; bumped on every nested
   :expr so repeated random/* calls within one resolution don't all return
   the same result)."
  [value locals seed]
  (let [seed (long (or seed 0))]
    (cond
      (and (map? value) (vector? (:ref value)) (= :local (first (:ref value))))
      (let [[_ local-name & path] (:ref value)]
        (if (seq path) (get-in (get locals local-name) (vec path)) (get locals local-name)))

      (and (map? value) (keyword? (:expr value)))
      (let [args (mapv #(resolve-value % locals (expr/next-seed seed)) (:args value))]
        (expr/evaluate (:expr value) args seed))

      (map? value)
      (reduce-kv (fn [acc k v] (assoc acc k (resolve-value v locals seed))) {} value)

      (vector? value)
      (mapv #(resolve-value % locals seed) value)

      :else value)))
