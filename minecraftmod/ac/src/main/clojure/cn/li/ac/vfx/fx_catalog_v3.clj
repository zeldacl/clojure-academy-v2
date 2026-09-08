(ns cn.li.ac.vfx.fx-catalog-v3
  "Directory-backed V3 VFX catalog. One document is one public effect;
   there is no manifest or source indirection. Runtime compilation is lazy,
   but every document is validated at assembly so editor errors are surfaced
   before a client accepts a signal.

   Enumeration matches the skill catalog: Loom runClient `union:` URLs and
   jars without directory entries still resolve via sentinel file walk."
  (:require [clojure.java.io :as io]
            [cn.li.ac.util.classpath-edn :as classpath-edn]
            [cn.li.mcmod.util.log :as log]
            [cn.li.node.api :as node-api]))

(defn resource-names [root]
  (classpath-edn/edn-resource-names
   root
   {:sentinels ["beam-arc-fade.edn" "beam-session.edn"]}))

(defn- read-resource [resource]
  (let [url (or (classpath-edn/find-resource resource) (io/resource resource))]
    (when-not url (throw (ex-info "V3 VFX resource not found" {:resource resource})))
    (binding [*read-eval* false] (read-string (slurp url)))))

(defn- user-types [document]
  (into {} (map (fn [[key spec]] [key (:type spec)])) (:inputs document)))

(defn- read-effect! [resource]
  (let [document (read-resource resource)]
    (node-api/validate-document! document)
    (when-not (= :vfx (node-api/document-kind document))
      (throw (ex-info "V3 VFX catalog contains non-VFX document"
                      {:resource resource :schema (:schema document)})))
    {:resource resource
     :id (:id document)
     :document document
     :user-types (user-types document)
     :emitters (:emitters document)
     :lifecycle (get-in document [:lifecycle :mode])}))

(defn assemble
  ([] (assemble {}))
  ([{:keys [resource-root] :or {resource-root "ac/vfx-v3"}}]
   (let [resources (resource-names resource-root)
         _ (when (empty? resources)
             (log/warn "VFX catalog enumerated no EDN documents"
                       {:resource-root resource-root
                        :sentinel-found? (boolean (classpath-edn/find-resource
                                                   (str resource-root "/beam-arc-fade.edn")))}))
         effects (mapv read-effect! resources)
         ids (map :id effects)]
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "V3 VFX ids must be unique" {:ids ids})))
     {:effects effects
      :by-id (into {} (map (juxt :id identity)) effects)
      :resource-count (count resources)})))
