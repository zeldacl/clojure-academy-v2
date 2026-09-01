(ns cn.li.node.composite-loader
  "Generic loading of :layer :composite composite documents from a manifest and
   individual EDN resources. A manifest lists {:kind :composite :id :resource}
   entries; each resource is read via an injected `document-loader` supplied
   by the mcmod boundary. Any malformed document fails the composition root
   closed; partial registries are never published.

   A composite document read this way is immutable descriptor data; this
   namespace only performs the resource-loading step and never interprets a
   composite at runtime."
  (:require [cn.li.node.descriptor :as node]))

(def ^:const schema-version
  "Lifted from ac/final_catalog.clj's validate-manifest, which checked this
   on every manifest it loaded (combat and vfx alike) before this loader
   existed to do it centrally -- a real check the manifest shape had, that
   this generic loader had silently dropped when combat/vfx composite
   manifests started routing through it instead."
  1)

(defn- fail [message data]
  (throw (ex-info message data)))

(defn load-manifest!
  [manifest-resource document-loader]
  (let [manifest (document-loader manifest-resource)]
    (when-not (and (map? manifest) (vector? (:documents manifest)))
      (fail "invalid composite manifest" {:resource manifest-resource}))
    (when-not (= schema-version (:schema-version manifest))
      (fail "unsupported composite manifest schema version"
            {:resource manifest-resource :schema-version (:schema-version manifest)}))
    (let [documents (:documents manifest)]
      (when-not (= (count documents) (count (set (map :id documents))))
        (fail "composite manifest contains duplicate ids" {:resource manifest-resource})))
    manifest))

(defn load-documents
  "Read and validate every composite document into an immutable id->descriptor map.
   One invalid document aborts the whole load."
  [{:keys [manifest-resource document-loader]}]
  (when-not (ifn? document-loader)
    (fail "document-loader is required" {:manifest-resource manifest-resource}))
  (let [manifest (load-manifest! manifest-resource document-loader)]
    {:documents
     (reduce
      (fn [result {:keys [kind id resource]}]
        (when-not (= :composite kind)
          (fail "unsupported composite manifest kind" {:kind kind :id id}))
        (let [document (document-loader resource)]
          (when-not (= id (:id document))
            (fail "composite manifest/document id mismatch"
                  {:manifest-id id :document-id (:id document)}))
          (when-not (and (= :composite (:layer document))
                         (map? (:inputs document))
                         (map? (:body document)))
            (fail "invalid v3 composite document" {:id id}))
          (assoc result id (node/normalize document))))
      {}
      (:documents manifest))}))

