(ns cn.li.node.schema-export
  "Export only the author-facing portion of the node ABI.

   Runtime injection, capabilities, kernel descriptors and host transaction
   details are intentionally excluded from the visual editor schema.")

(defn- export-fields [fields]
  (reduce-kv (fn [acc k spec]
               (assoc acc k (select-keys spec [:type :min :max :default :doc :scope])))
             {} fields))

(defn export-descriptor [d]
  (when (= :author (:visibility d))
    {:id (:id d)
     :revision (:revision d)
     :layer (:layer d)
     :doc (:doc d)
     :category (:category d)
     :inputs (export-fields (:inputs d))
     :outputs (export-fields (:outputs d))
     :children (reduce-kv (fn [acc k spec]
                            (assoc acc k (select-keys spec [:kind :flow :required?])))
                          {} (:children d))
     :effects (:effects d)}))

(defn export-environment
  "Export one immutable author-facing node catalog in stable id order."
  [environment]
  (->> (vals (:descriptors environment))
       (sort-by :id)
       (keep export-descriptor)
       vec))


