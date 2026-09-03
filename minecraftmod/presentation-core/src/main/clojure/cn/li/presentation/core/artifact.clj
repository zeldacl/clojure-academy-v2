(ns cn.li.presentation.core.artifact
  "Runtime loader for build-generated Presentation UI artifacts.

   Runtime only reads .uic.edn and the generated manifest from the classpath;
   source .ui.edn and presentation-compiler are build-time concerns."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- manifest-resource-for [content-id]
  (str "META-INF/presentation/" (name content-id) ".catalog.edn"))
(def ^:private artifact-magic :pui5)
(def ^:private manifest-magic :pui5-catalog)
(def ^:private artifact-schema 5)

(defn- read-resource [resource]
  (when-let [url (io/resource resource)]
    (with-open [reader (java.io.PushbackReader.
                         (io/reader url :encoding "UTF-8"))]
      (edn/read {:eof nil} reader))))

(defn load-manifest
  "Load and validate ONE content module's manifest by content-id. Every
   content module compiles into its own META-INF/presentation/<content-id>.
   catalog.edn (presentation-compiler's compile-directory!, one gradle task
   per module) -- this reads exactly that one tenant's file, never a merged
   view."
  [content-id]
  (let [resource (manifest-resource-for content-id)
        manifest (or (read-resource resource)
                     (throw (ex-info "Presentation UI manifest missing"
                                     {:content-id content-id :resource resource})))]
    (when-not (= manifest-magic (:magic manifest))
      (throw (ex-info "invalid Presentation UI manifest"
                      {:resource resource :manifest manifest})))
    (when-not (= artifact-schema (:schema manifest))
      (throw (ex-info "unsupported Presentation UI manifest schema"
                      {:schema (:schema manifest)})))
    manifest))

(defn merge-manifests
  "Merge N content modules' manifests (by content-id) into one. Every
   per-frame/per-mount read goes through the merged value returned here, not
   through this function repeatedly -- callers build it once at bootstrap
   (see cn.li.ac.gui.presentation's delay) and reuse it, matching how
   cn.li.ability.registry merges combat/vfx bundles once instead of on every
   read.

   Throws, naming both owning content-ids, if two tenants register the same
   view-id -- the alternative (first-write-wins) is exactly the silent
   cross-tenant collision this refactor exists to remove everywhere else
   (mcmod capabilities, vfx-core's client registry, ability.registry)."
  [content-ids]
  (let [manifests (map (fn [cid] [cid (load-manifest cid)]) content-ids)
        views (reduce
               (fn [acc [content-id manifest]]
                 (reduce-kv
                  (fn [acc view-id entry]
                    (when-let [owner (get-in acc [view-id ::owner])]
                      (throw (ex-info "Presentation UI view id collision between content modules"
                                      {:view-id view-id :owners [owner content-id]})))
                    (assoc acc view-id (assoc entry ::owner content-id)))
                  acc (:views manifest)))
               {} manifests)]
    {:magic manifest-magic
     :schema artifact-schema
     :content-ids (vec content-ids)
     :views (into (sorted-map) (map (fn [[k v]] [k (dissoc v ::owner)])) views)
     :generated-by :presentation-compiler}))

(defn- view-entry [manifest view-id]
  (or (get-in manifest [:views view-id])
      (get-in manifest [:views (keyword (namespace view-id) (name view-id))])
      (throw (ex-info "Presentation UI view is not registered"
                      {:view-id view-id}))))

(defn load-view
  "Load and validate one immutable compiled view from the runtime classpath.
   manifest must already be loaded -- see load-manifest/merge-manifests --
   since a single content-id-free default no longer exists once a build can
   have more than one content module's manifest on the classpath."
  [manifest view-id]
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
     artifact))

