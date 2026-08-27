(ns cn.li.node.schema-export
  "Machine-readable node catalog for tooling (a future visual editor's node
   palette, or a schema dump for review). One descriptor in, one plain-data
   map out -- deliberately dumb, so it cannot itself become a place where
   editor-specific logic accumulates."
  (:require [cn.li.node.descriptor :as registry]))

(defn- export-fields [fields]
  (reduce-kv (fn [acc k spec] (assoc acc k (select-keys spec [:type :min :max :default :doc :scope])))
             {} fields))

(defn export-descriptor [d]
  {:id (:id d)
   :revision (:revision d)
   :layer (:layer d)
   :doc (:doc d)
   :category (:category d)
   :inputs (export-fields (:inputs d))
   :outputs (export-fields (:outputs d))
   :children (reduce-kv (fn [acc k spec] (assoc acc k (select-keys spec [:kind :flow :required?])))
                         {} (:children d))
   :effects (:effects d)
   :reads-environment (:reads-environment d)})

(defn export-catalog
  "Every currently-registered descriptor as plain data, sorted by id for a
   stable diff/output order."
  []
  (mapv export-descriptor (sort-by :id (registry/all-descriptors))))

(defn export-environment
  "Export one immutable NodeEnvironment in stable id order."
  [environment]
  (mapv export-descriptor
        (sort-by :id (registry/environment-descriptors environment))))
