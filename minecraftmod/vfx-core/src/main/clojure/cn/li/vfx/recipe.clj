(ns cn.li.vfx.recipe
  "Safe static VFX EDN loader and startup compiler." 
  (:require [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.vfx.components :as components]
            [cn.li.vfx.ir :as ir])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private max-ir-nodes 4096)
(def ^:private max-composite-depth 16)
(def ^:private max-expanded-nodes 4096)
(def ^:private lifecycles #{:transient :persistent :singleton :session})

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

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- compile-node [compiler node path]
  (when-not (map? node)
    (fail "VFX component node must be a map" {:path path :node node}))
  (let [id (:component node)
        descriptor (components/descriptor id)]
    (when-not (keyword? id)
      (fail "VFX component requires keyword :component" {:path path}))
    (when-not descriptor
      (fail "unknown VFX component" {:path path :component id}))
    (doseq [field (get-in descriptor [:schema :required])]
      (when-not (contains? node field)
        (fail "VFX component field is missing"
              {:path path :component id :field field})))
    (let [compiler ((:lower descriptor) compiler node)]
      (when (> (count (:ir compiler)) max-ir-nodes)
        (fail "VFX program exceeds IR budget"
              {:path path :limit max-ir-nodes}))
      compiler)))

(defn- nested-nodes [node]
  (concat
    (map-indexed (fn [index child] [[:children index :node] child])
                 (keep :node (:children node)))
    (when-let [child (:child node)] [[[:child] child]])
    (map (fn [[key child]] [[:cases key] child])
         (filter (fn [[_ child]] (map? child)) (:cases node)))))

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
      (fail "VFX composite input is missing" {:path path :input key})
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
      (fail "VFX composite has unknown inputs"
            {:path path :component (:id descriptor) :inputs unknown}))
    (reduce-kv
      (fn [result key definition]
        (if (contains? supplied key)
          (assoc result key (get supplied key))
          (if (and (map? definition) (contains? definition :default))
            (assoc result key (:default definition))
            (fail "VFX composite input is missing"
                  {:path path :component (:id descriptor) :input key}))))
      {} definitions)))

(declare expand-node)

(defn- expand-node [node composites path stack depth]
  (when (> depth max-composite-depth)
    (fail "VFX composite expansion depth exceeded"
          {:path path :limit max-composite-depth}))
  (when-not (map? node)
    (fail "VFX component node must be a map" {:path path :node node}))
  (if-let [descriptor (get composites (:component node))]
    (let [id (:id descriptor)]
      (when (contains? stack id)
        (fail "VFX composite expansion cycle" {:path path :component id}))
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
  "Expand VFX composite components during catalog compilation."
  ([node composites] (expand-composite-tree node composites [:graph]))
  ([node composites path]
   (let [expanded (expand-node node composites path #{} 0)
         nodes (tree-seq map? #(map second (nested-nodes %)) expanded)]
     (when (> (count (filter map? nodes)) max-expanded-nodes)
       (fail "expanded VFX tree exceeds node budget"
             {:path path :limit max-expanded-nodes}))
     expanded)))

(defn- validate-tree [node path]
  (compile-node {:ir []} node path)
  (doseq [[suffix child] (nested-nodes node)]
    (validate-tree child (into path suffix)))
  node)

(defn compile-effect
  ([effect] (compile-effect effect {}))
  ([effect {:keys [composites] :or {composites {}}}]
  (when-not (keyword? (:id effect))
    (fail "VFX effect id must be keyword" {:effect effect}))
  (when-not (contains? lifecycles (:lifecycle effect))
    (fail "invalid VFX lifecycle" {:effect-id (:id effect)}))
  (when-not (map? (:graph effect))
    (fail "VFX effect requires graph" {:effect-id (:id effect)}))
  (components/register-builtins!)
  (let [expanded-graph (expand-composite-tree (:graph effect) composites)
        expanded-effect (assoc effect :graph expanded-graph)]
    (validate-tree expanded-graph [:graph])
    (let [compiled (compile-node {:ir []} expanded-graph [:graph])]
      (assoc expanded-effect
             :compiled? true
             :effect-hash (content-hash expanded-effect)
             :compiled-ir (:ir compiled)
             :compiled-program (ir/encode (:ir compiled)))))))

(defn load-manifest! [resource-path]
  (safe-edn/read-resource! resource-path))

(defn load-composites!
  [{:keys [manifest-resource document-loader]
    :or {document-loader safe-edn/read-resource!}}]
  (let [manifest (load-manifest! manifest-resource)
        documents (mapv (fn [{:keys [kind id resource]}]
                          (when-not (= :composite kind)
                            (fail "unsupported VFX composite kind" {:kind kind}))
                          (let [composite (document-loader resource)]
                            (when-not (= id (:id composite))
                              (fail "VFX composite manifest/document id mismatch"
                                    {:manifest-id id :document-id (:id composite)}))
                            (when-not (and (= :composite (:kind composite))
                                           (keyword? (:id composite))
                                           (integer? (:revision composite))
                                           (map? (:inputs composite))
                                           (map? (:body composite)))
                              (fail "invalid VFX composite document"
                                    {:id id :document composite}))
                            composite))
                        (:documents manifest))]
    (when-not (= (count documents) (count (set (map :id documents))))
      (fail "duplicate VFX composite id" {:ids (map :id documents)}))
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
        effects (mapv (fn [{:keys [kind id resource]}]
                        (when-not (= :vfx-effect kind)
                          (fail "unsupported VFX document kind" {:kind kind}))
                        (let [effect (compile-effect
                                       (document-loader resource)
                                       {:composites composites})]
                          (when-not (= id (:id effect))
                            (fail "VFX manifest/document id mismatch"
                                  {:manifest-id id :document-id (:id effect)}))
                          effect))
                      (:documents manifest))]
    {:schema-version 1
     :manifest manifest
     :composites composites
     :effects (into {} (map (juxt :id identity) effects))
     :content-hash (content-hash {:effects effects :composites composites})}))
