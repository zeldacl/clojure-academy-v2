(ns cn.li.node.validate
  "Structural validation of an already-expanded (see cn.li.node.composite)
   node tree: unknown component, unknown field, missing required field,
   literal type mismatch. Runs after composite expansion so every node here
   is a real primitive (or a top-level source) with a real :impl -- this is
   deliberately independent of cn.li.node.scope (which only proves binding
   safety) and of cn.li.node.composite (which only proves expansion
   terminates); together the three cover shape, binding, and expansion."
  (:require [cn.li.node.environment :as registry]
            [cn.li.node.types :as types]))

(defn- fail [reason data]
  (throw (ex-info (name reason) (assoc data :reason reason))))

(def ^:dynamic *environment* nil)

(defn- callback-input-keys [d]
  (set (keep (fn [[k spec]] (when (= :node (:type spec)) k)) (:inputs d))))

(defn- validate-fields!
  [node d path]
  (let [structural (into #{:component :bind} (keys (:children d)))
        declared (:inputs d)
        supplied (apply dissoc node structural)
        unknown (remove declared (keys supplied))]
    (when (seq unknown)
      (fail :unknown-field {:path path :component (:component node) :fields (vec unknown)}))
    (doseq [[k spec] declared]
      (cond
        (not (contains? node k))
        (when-not (contains? spec :default)
          (fail :missing-required-field {:path path :component (:component node) :field k}))

        (and (not= :node (:type spec)) (not (types/deferred? (get node k)))
             (not (types/conforms? (:type spec) (get node k))))
        (fail :type-mismatch {:path path :component (:component node) :field k
                               :expected (:type spec) :value (get node k)})))))

(declare validate-node!)

(defn- validate-child [child path]
  (when (map? child) (validate-node! child path)))

(defn validate-node! [node path]
  (when-not (map? node) (fail :not-a-node {:path path}))
  (let [component (:component node)
        d (registry/descriptor *environment* component)]
    (when-not d (fail :unknown-component {:path path :component component}))
    (validate-fields! node d path)
    (doseq [[key {:keys [kind]}] (:children d)]
      (case kind
        :single (validate-child (get node key) (conj path key))
        :seq (doseq [child (filterv map? (get node key))] (validate-node! child (conj path key)))
        :case-map (doseq [[case-key child] (get node key {})] (validate-child child (conj path [key case-key])))
        nil))
    (doseq [key (callback-input-keys d)]
      (validate-child (get node key) (conj path key)))))

(defn validate-in-environment!
  "Validate a graph against one immutable descriptor environment."
  [environment root]
  (binding [*environment* environment]
    (validate-node! root [:program])
    nil))
