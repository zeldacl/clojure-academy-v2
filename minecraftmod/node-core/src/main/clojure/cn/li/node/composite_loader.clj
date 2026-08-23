(ns cn.li.node.composite-loader
  "Generic loading of :layer :mid composite documents from a manifest +
   individual EDN resources into node-core's registry -- mirrors combat-
   core's pre-existing v2 pattern (recipe.clj's load-composites!) so both
   language layers use the same shape: a manifest lists {:kind :composite
   :id :resource}, each resource is read via an injected `document-loader`
   (real content uses cn.li.mcmod.runtime.safe-edn/read-resource! --
   strict, tag-free, data-only -- but this namespace cannot depend on
   mcmod directly, so the caller supplies it, exactly like recipe.clj's
   own :document-loader parameter already does), and one bad document
   disables only itself (Design E: fail-closed per document, not
   globally) rather than the whole registry load.

   A composite document read this way is, byte for byte, the same map
   cn.li.node.descriptor/register-composite! already accepts -- composites
   were always meant to be authored as data, this namespace is just the
   'read the data from a file' step that was still missing."
  (:require [cn.li.node.descriptor :as node]))

(defn- fail [message data]
  (throw (ex-info message data)))

(defn load-manifest!
  [manifest-resource document-loader]
  (let [manifest (document-loader manifest-resource)]
    (when-not (and (map? manifest) (vector? (:documents manifest)))
      (fail "invalid composite manifest" {:resource manifest-resource}))
    manifest))

(defn install!
  "Read `manifest-resource` (a path `document-loader` understands, e.g. a
   classpath resource path) listing {:kind :composite :id :resource}
   entries, and register-composite! every document that parses and
   validates. `document-loader` is a (fn [resource-path] -> parsed-edn),
   required (not defaulted here -- see this namespace's docstring for why).

   Returns {:registered [id...] :errors [{:id :error :data} ...]}. A
   document that fails to parse, whose :id doesn't match the manifest
   entry, or whose shape isn't a well-formed :layer :mid composite (or
   whose registration itself fails, e.g. a duplicate id) is recorded in
   :errors and skipped; every other document still loads."
  [{:keys [manifest-resource document-loader]}]
  (when-not (ifn? document-loader)
    (fail "document-loader is required" {:manifest-resource manifest-resource}))
  (let [manifest (load-manifest! manifest-resource document-loader)]
    (reduce
     (fn [result {:keys [kind id resource]}]
       (try
         (when-not (= :composite kind)
           (fail "unsupported composite manifest kind" {:kind kind :id id}))
         (let [document (document-loader resource)]
           (when-not (= id (:id document))
             (fail "composite manifest/document id mismatch"
                   {:manifest-id id :document-id (:id document)}))
           (when-not (and (= :mid (:layer document)) (map? (:inputs document)) (map? (:body document)))
             (fail "invalid v3 composite document" {:id id}))
           (node/register-composite! document))
         (update result :registered conj id)
         (catch Throwable throwable
           (update result :errors conj {:id id :error (ex-message throwable) :data (ex-data throwable)}))))
     {:registered [] :errors []}
     (:documents manifest))))
