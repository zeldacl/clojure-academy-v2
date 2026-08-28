(ns cn.li.presentation.core.artifact
  "Runtime loader for build-generated Presentation UI artifacts.

   Runtime only reads .uic.edn and the generated manifest from the classpath;
   source .ui.edn and presentation-compiler are build-time concerns."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private manifest-resource
  "META-INF/presentation/catalog.edn")
(def ^:private artifact-magic :pui3)
(def ^:private manifest-magic :pui3-catalog)
(def ^:private artifact-schema 3)

(defn- read-resource [resource]
  (when-let [url (io/resource resource)]
    (with-open [reader (java.io.PushbackReader.
                         (io/reader url :encoding "UTF-8"))]
      (edn/read {:eof nil} reader))))

(defn load-manifest []
  (let [manifest (read-resource manifest-resource)]
    (when-not (= manifest-magic (:magic manifest))
      (throw (ex-info "invalid Presentation UI manifest"
                      {:resource manifest-resource :manifest manifest})))
    (when-not (= artifact-schema (:schema manifest))
      (throw (ex-info "unsupported Presentation UI manifest schema"
                      {:schema (:schema manifest)})))
    manifest))

(defn- view-entry [manifest view-id]
  (or (get-in manifest [:views view-id])
      (get-in manifest [:views (keyword (namespace view-id) (name view-id))])
      (throw (ex-info "Presentation UI view is not registered"
                      {:view-id view-id}))))

(defn load-view
  "Load and validate one immutable compiled view from the runtime classpath." 
  ([view-id] (load-view (load-manifest) view-id))
  ([manifest view-id]
   (let [{:keys [resource source-hash schema]} (view-entry manifest view-id)
         artifact (or (read-resource resource)
                      (throw (ex-info "compiled Presentation UI artifact missing"
                                      {:view-id view-id :resource resource})))]
     (when-not (= artifact-magic (:magic artifact))
       (throw (ex-info "invalid Presentation UI artifact"
                       {:view-id view-id :resource resource})))
     (when-not (= artifact-schema (:schema artifact))
       (throw (ex-info "unsupported Presentation UI artifact schema"
                       {:view-id view-id :schema (:schema artifact)})))
     (when-not (= view-id (:view-id artifact))
       (throw (ex-info "Presentation UI artifact view id mismatch"
                       {:expected view-id :actual (:view-id artifact)})))
     (when (and source-hash (not= source-hash (:source-hash artifact)))
       (throw (ex-info "Presentation UI artifact digest mismatch"
                       {:view-id view-id :expected source-hash
                        :actual (:source-hash artifact)})))
     artifact)))

