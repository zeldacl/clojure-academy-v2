(ns cn.li.combat.recipe
  "Safe static EDN catalog loader and generic component compiler." 
  (:require [cn.li.combat.components :as components]
            [cn.li.combat.ir :as ir]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [clojure.java.io :as io])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private max-ir-nodes 4096)
(def ^:private max-composite-depth 16)
(def ^:private max-expanded-nodes 4096)
(def ^:private supported-activations #{:instant :session :toggle :passive})

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key nested]] [key (canonical nested)]))
                       value)
    (vector? value) (mapv canonical value)
    :else value))

(defn content-hash [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (canonical value))
                                   StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- validate-node! [node path]
  (when-not (map? node)
    (fail "component node must be a map" {:path path :node node}))
  (when-not (keyword? (:component node))
    (fail "component node requires keyword :component" {:path path :node node}))
  (let [descriptor (components/descriptor (:component node))]
    (when-not descriptor
      (fail "unknown component" {:path path :component (:component node)}))
    (doseq [field (get-in descriptor [:schema :required])]
      (when-not (contains? node field)
        (fail "component field is missing"
              {:path path :component (:component node) :field field})))
    node))

(defn- compile-node [compiler node path]
  (validate-node! node path)
  (let [component (components/descriptor (:component node))
        compiler ((:lower component) compiler node)]
    (when (> (count (:ir compiler)) max-ir-nodes)
      (fail "combat program exceeds IR budget"
            {:path path :limit max-ir-nodes}))
    compiler))

(defn- nested-nodes [node]
  (let [single (keep #(when (map? (get node %)) [[(keyword %)] (get node %)])
                     [:start :pulse :release :abort :then :else :body
                      :on-pass :on-fail :on-first :on-duplicate :on-impact
                      :on-hit :on-miss :on-success :child :block-policy
                      :interaction])
        vectors (concat
                  (map-indexed (fn [index value] [[:steps index] value])
                               (filter map? (:steps node)))
                  (map-indexed (fn [index value] [[:guards index] value])
                               (filter map? (:guards node)))
                  (map-indexed (fn [index value] [[:reservations index] value])
                               (filter map? (:reservations node)))
                  (map-indexed (fn [index value] [[:children index :node] (:node value)])
                               (filter #(map? (:node %)) (:children node))))
        maps (concat
               (map (fn [[key value]] [[:events key] value])
                    (filter (fn [[_ value]] (map? value)) (:events node)))
               (map (fn [[key value]] [[:cases key] value])
                    (filter (fn [[_ value]] (map? value)) (:cases node))))]
     (concat single vectors maps)))

(defn- input-reference [value]
  (let [reference (:ref value)]
    (when (and (map? value)
               (vector? reference)
               (= :input (first reference))
               (keyword? (second reference)))
      [(second reference) (subvec reference 2)])))

(defn- substitute-inputs [value inputs path]
  (if-let [[key suffix] (input-reference value)]
    (if-not (contains? inputs key)
      (fail "composite input is missing" {:path path :input key})
      (let [replacement (get inputs key)]
        (if (seq suffix)
          (get-in replacement suffix)
          replacement)))
    (cond
      (map? value)
      (reduce-kv (fn [result key nested]
                   (assoc result key
                          (substitute-inputs nested inputs (conj path key))))
                 (empty value) value)
      (vector? value)
      (mapv #(substitute-inputs % inputs path) value)
      :else value)))

(defn- composite-inputs [descriptor node path]
  (let [definitions (:inputs descriptor)
        supplied (dissoc node :component)
        unknown (seq (remove #(contains? definitions %) (keys supplied)))]
    (when unknown
      (fail "composite has unknown inputs"
            {:path path :component (:id descriptor) :inputs unknown}))
    (reduce-kv
      (fn [result key definition]
        (if (contains? supplied key)
          (assoc result key (get supplied key))
          (if (and (map? definition) (contains? definition :default))
            (assoc result key (:default definition))
            (fail "composite input is missing"
                  {:path path :component (:id descriptor) :input key}))))
      {} definitions)))

(declare expand-node)

(defn- expand-node [node composites path stack depth]
  (when (> depth max-composite-depth)
    (fail "composite expansion depth exceeded"
          {:path path :limit max-composite-depth}))
  (when-not (map? node)
    (fail "component node must be a map" {:path path :node node}))
  (if-let [descriptor (get composites (:component node))]
    (let [id (:id descriptor)]
      (when (contains? stack id)
        (fail "composite expansion cycle" {:path path :component id}))
      (let [inputs (composite-inputs descriptor node path)
            body (substitute-inputs (:body descriptor) inputs path)]
        (expand-node body composites path (conj stack id) (inc depth))))
    (reduce (fn [result [suffix child]]
              (assoc-in result suffix
                        (expand-node child composites (into path suffix)
                                     stack depth)))
            node
            (nested-nodes node))))

(defn expand-composite-tree
  "Expand composite components once during catalog compilation."
  ([node composites] (expand-composite-tree node composites [:program]))
  ([node composites path]
   (let [expanded (expand-node node composites path #{} 0)
         nodes (tree-seq map? #(map second (nested-nodes %)) expanded)]
     (when (> (count (filter map? nodes)) max-expanded-nodes)
       (fail "expanded combat tree exceeds node budget"
             {:path path :limit max-expanded-nodes}))
     expanded)))

(defn- validate-tree [node path]
  (validate-node! node path)
  (doseq [[suffix child] (nested-nodes node)]
    (validate-tree child (into path suffix)))
  node)

(defn- compile-tree [compiler node path]
  ;; Nested nodes remain embedded in the root component constant.  They are
  ;; validated here, then interpreted by the root component's Clojure
  ;; implementation; emitting each child as a second top-level opcode would
  ;; execute inactive phases as well.
  (validate-tree node path)
  (compile-node compiler node path))

(defn compile-ability
  ([ability] (compile-ability ability {}))
  ([ability {:keys [composites] :or {composites {}}}]
  (components/register-builtins!)
  (when-not (keyword? (:id ability))
    (fail "ability id must be a keyword" {:ability ability}))
  (when-not (contains? supported-activations (:activation ability))
    (fail "unsupported ability activation"
          {:id (:id ability) :activation (:activation ability)}))
  (when-not (map? (:program ability))
    (fail "ability requires :program" {:id (:id ability)}))
  (let [expanded-program (expand-composite-tree (:program ability) composites)
        expanded-ability (assoc ability :program expanded-program)
        compiler (compile-tree
                   {:ir [] :slots {:double 0 :long 0 :boolean 0 :object 0}}
                   expanded-program
                   [:program])]
    (assoc expanded-ability
           :compiled? true
           :program-hash (content-hash expanded-ability)
           :compiled-ir (:ir compiler)
           :slot-counts (:slots compiler)
           :compiled-program (ir/encode (:ir compiler) (:slots compiler))))))

(defn load-manifest! [resource-path]
  (safe-edn/read-resource! resource-path))

(defn load-composites!
  [{:keys [manifest-resource document-loader]
    :or {document-loader safe-edn/read-resource!}}]
  (let [manifest (load-manifest! manifest-resource)
        documents (mapv (fn [{:keys [kind id resource]}]
                          (when-not (= :composite kind)
                            (fail "unsupported combat composite kind" {:kind kind}))
                          (let [composite (document-loader resource)]
                            (when-not (= id (:id composite))
                              (fail "composite manifest/document id mismatch"
                                    {:manifest-id id :document-id (:id composite)}))
                            (when-not (and (= :composite (:kind composite))
                                           (keyword? (:id composite))
                                           (integer? (:revision composite))
                                           (map? (:inputs composite))
                                           (map? (:body composite)))
                              (fail "invalid combat composite document"
                                    {:id id :document composite}))
                            composite))
                        (:documents manifest))]
    (when-not (= (count documents) (count (set (map :id documents))))
      (fail "duplicate combat composite id" {:ids (map :id documents)}))
    {:schema-version 1
     :manifest manifest
     :composites (into {} (map (juxt :id identity) documents))
     :content-hash (content-hash documents)}))

(defn load-catalog!
  [{:keys [manifest-resource composites-manifest-resource document-loader]
    :or {document-loader safe-edn/read-resource!}}]
  (components/register-builtins!)
  (let [manifest (load-manifest! manifest-resource)
        composites (if composites-manifest-resource
                     (:composites (load-composites!
                                    {:manifest-resource composites-manifest-resource
                                     :document-loader document-loader}))
                     {})
        documents (mapv (fn [{:keys [kind id resource]}]
                          (when-not (= :ability kind)
                            (fail "unsupported combat document kind" {:kind kind}))
                          (let [ability (document-loader resource)]
                            (when-not (= id (:id ability))
                              (fail "manifest/document id mismatch"
                                    {:manifest-id id :document-id (:id ability)}))
                            (compile-ability ability {:composites composites})))
                        (:documents manifest))]
    {:schema-version 1
     :manifest manifest
     :composites composites
     :abilities (into {} (map (juxt :id identity) documents))
     :content-hash (content-hash {:abilities documents :composites composites})}))
