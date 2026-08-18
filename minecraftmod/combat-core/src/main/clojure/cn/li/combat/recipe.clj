(ns cn.li.combat.recipe
  "Safe static EDN catalog loader and generic component compiler." 
  (:require [cn.li.combat.components :as components]
            [cn.li.combat.ir :as ir]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [clojure.java.io :as io])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private max-ir-nodes 4096)
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
                      :on-pass :on-fail :on-first :on-duplicate :child
                      :block-policy :interaction])
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

(defn compile-ability [ability]
  (components/register-builtins!)
  (when-not (keyword? (:id ability))
    (fail "ability id must be a keyword" {:ability ability}))
  (when-not (contains? supported-activations (:activation ability))
    (fail "unsupported ability activation"
          {:id (:id ability) :activation (:activation ability)}))
  (when-not (map? (:program ability))
    (fail "ability requires :program" {:id (:id ability)}))
  (let [compiler (compile-tree
                   {:ir [] :slots {:double 0 :long 0 :boolean 0 :object 0}}
                   (:program ability)
                   [:program])]
    (assoc ability
           :compiled? true
           :program-hash (content-hash ability)
           :compiled-ir (:ir compiler)
           :slot-counts (:slots compiler)
           :compiled-program (ir/encode (:ir compiler) (:slots compiler)))))

(defn load-manifest! [resource-path]
  (safe-edn/read-resource! resource-path))

(defn load-catalog!
  [{:keys [manifest-resource document-loader]
    :or {document-loader safe-edn/read-resource!}}]
  (components/register-builtins!)
  (let [manifest (load-manifest! manifest-resource)
        documents (mapv (fn [{:keys [kind id resource]}]
                          (when-not (= :ability kind)
                            (fail "unsupported combat document kind" {:kind kind}))
                          (let [ability (document-loader resource)]
                            (when-not (= id (:id ability))
                              (fail "manifest/document id mismatch"
                                    {:manifest-id id :document-id (:id ability)}))
                            (compile-ability ability)))
                        (:documents manifest))]
    {:schema-version 1
     :manifest manifest
     :abilities (into {} (map (juxt :id identity) documents))
     :content-hash (content-hash documents)}))
