(ns cn.li.vfx.recipe
  "Safe static VFX EDN loader and startup compiler." 
  (:require [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.vfx.components :as components]
            [cn.li.vfx.ir :as ir])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private max-ir-nodes 4096)
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

(defn- validate-tree [node path]
  (compile-node {:ir []} node path)
  (doseq [[suffix child] (nested-nodes node)]
    (validate-tree child (into path suffix)))
  node)

(defn compile-effect [effect]
  (when-not (keyword? (:id effect))
    (fail "VFX effect id must be keyword" {:effect effect}))
  (when-not (contains? lifecycles (:lifecycle effect))
    (fail "invalid VFX lifecycle" {:effect-id (:id effect)}))
  (when-not (map? (:graph effect))
    (fail "VFX effect requires graph" {:effect-id (:id effect)}))
  (components/register-builtins!)
  (validate-tree (:graph effect) [:graph])
  (let [compiled (compile-node {:ir []} (:graph effect) [:graph])]
    (assoc effect
           :compiled? true
           :effect-hash (content-hash effect)
           :compiled-ir (:ir compiled)
           :compiled-program (ir/encode (:ir compiled)))))

(defn load-catalog!
  [{:keys [manifest-resource]}]
  (components/register-builtins!)
  (let [manifest (safe-edn/read-resource! manifest-resource)
        effects (mapv (fn [{:keys [id resource]}]
                        (let [effect (compile-effect
                                       (safe-edn/read-resource! resource))]
                          (when-not (= id (:id effect))
                            (fail "VFX manifest/document id mismatch"
                                  {:manifest-id id :document-id (:id effect)}))
                          effect))
                      (:documents manifest))]
    {:schema-version 1
     :manifest manifest
     :effects (into {} (map (juxt :id identity) effects))
     :content-hash (content-hash effects)}))
