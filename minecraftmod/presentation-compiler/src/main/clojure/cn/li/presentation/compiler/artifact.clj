(ns cn.li.presentation.compiler.artifact
  "Deterministic, data-only template artifact IO.

   The wire format is intentionally EDN data with a fixed magic header.  It
   is not an executable Clojure form: decode uses clojure.edn/read-string and
   reconstructs the small Java records explicitly.  This keeps production
   loading independent from source files while leaving all policy in Clojure."
  (:require [clojure.edn :as edn])
  (:import [cn.li.presentation.compiler CompiledTemplate TemplateNode]
           [cn.li.presentation.core ActionId TemplateId]
           [java.nio.charset StandardCharsets]))

(def ^:private magic "ACPRT1\n")

(defn- node->data [^TemplateNode node]
  {:type (.type node)
   :key (.key node)
   :children (mapv node->data (.children node))
   :bindings (into {} (.bindings node))
   :actions (into {} (map (fn [[k v]] [k (.value ^ActionId v)]) (.actions node)))
   :props (into {} (.props node))})

(defn- data->node [{:keys [type key children bindings actions props]}]
  (TemplateNode. (str type) (str key) (mapv data->node children)
                 (into {} (map (fn [[k v]] [(str k) (int v)]) bindings))
                 (into {} (map (fn [[k v]] [(str k) (ActionId. (int v))]) actions))
                 (or props {})))

(defn encode
  "Return a byte array containing a versioned compiled-template artifact.

   `metadata` is optional build metadata (backend capabilities, dependencies,
   and source revision) and is stored as plain EDN data."
  ([^CompiledTemplate template] (encode template {}))
  ([^CompiledTemplate template metadata]
   (let [data {:id (-> template .id .value)
               :content-hash (.contentHash template)
               :schema-version (.schemaVersion template)
               :root (node->data (.root template))
               :bindings (.bindings template)
               :actions (.actions template)
               :metadata (or metadata {})}]
     (.getBytes (str magic (pr-str data)) StandardCharsets/UTF_8))))

(defn decode
  "Decode bytes produced by `encode`; reject unknown or malformed artifacts."
  [^bytes bytes]
  (let [text (String. bytes StandardCharsets/UTF_8)]
    (when-not (.startsWith text magic)
      (throw (ex-info "invalid presentation artifact header" {})))
    (let [{:keys [id content-hash schema-version root bindings actions metadata]}
          (edn/read-string (subs text (count magic)))]
      (when-not (and (string? id) (string? content-hash)
                     (integer? schema-version) (map? root)
                     (map? bindings) (map? actions) (map? metadata))
        (throw (ex-info "invalid presentation artifact payload" {})))
      {:template (CompiledTemplate.
                  (TemplateId. id) content-hash (int schema-version)
                  (data->node root) bindings actions)
       :metadata metadata})))
