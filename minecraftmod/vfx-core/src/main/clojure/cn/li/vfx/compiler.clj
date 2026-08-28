(ns cn.li.vfx.compiler
  "Pure VFX graph lowering.  Composite expansion is deliberately independent
   of the combat/node-core compiler: VFX uses only EDN descriptors and keeps
   its four-stage execution contract explicit."
  (:require [clojure.walk :as walk]))

(def ^:const max-depth 16)
(def ^:const max-nodes 4096)

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- substitute-inputs [value inputs]
  (walk/postwalk
   (fn [form]
     (if (and (map? form) (vector? (:ref form)) (= :input (first (:ref form))))
       (let [[_ key & path] (:ref form)]
         (if (contains? inputs key)
           (let [replacement (get inputs key)]
             (if (seq path) (get-in replacement path) replacement))
           form))
       form)) value))

(defn- expand-node [value composites depth budget]
  (vswap! budget inc)
  (when (> depth max-depth) (fail "VFX composite expansion depth exceeded" {:max max-depth}))
  (when (> @budget max-nodes) (fail "VFX composite expansion node budget exceeded" {:max max-nodes}))
  (cond
    (map? value)
    (let [component (:component value)
          descriptor (when component (get composites component))]
      (if (and descriptor (= :composite (:layer descriptor)))
        (let [declared (or (:inputs descriptor) {})
              supplied (dissoc value :component)
              unknown (remove #(contains? declared %) (keys supplied))
              _ (when (seq unknown)
                  (fail "unknown VFX composite input" {:component component :fields unknown}))
              inputs (reduce-kv (fn [result key spec]
                                  (cond
                                    (contains? supplied key) (assoc result key (get supplied key))
                                    (contains? spec :default) (assoc result key (:default spec))
                                    :else (fail "missing VFX composite input" {:component component :field key})))
                                {} declared)
              body (substitute-inputs (:body descriptor) inputs)]
          (expand-node body composites (inc depth) budget))
        (into (empty value)
              (map (fn [[key child]] [key (expand-node child composites depth budget)]) value))))
    (sequential? value) (mapv #(expand-node % composites depth budget) value)
    :else value))

(defn expand-graph [graph composites]
  (expand-node graph (or composites {}) 0 (volatile! 0)))
