(ns cn.li.ac.vfx.fx-catalog
  "VFX cutover: AC content assembly for the NEW vfx-core engine (the new
   scene DSL + client-runtime, reached via cn.li.ability.client-vfx-v2),
   parallel to cn.li.ac.ability.final-catalog-service's own :vfx section
   (the old engine's loader).

   Reads ac/vfx/fx/manifest.edn (an exact copy of ac/vfx/manifest.edn
   with only the :resource paths repointed at ac/vfx/fx/*.edn -- the same
   36 effects, 1:1, no source-id sharing since VFX has no cross-effect
   registration reuse the way skills share one source across several
   registrations).

   Unlike cn.li.ac.ability.skills-catalog, this does NOT compile :scene
   text to IR at assembly time: the new engine's own client-runtime
   compiles per-instance, lazily, at instance-creation time (see that
   namespace's own docstring for why) -- this loader only has to produce
   the raw registry shape its create-runtime function expects."
  (:require [clojure.java.io :as io]))

(defn- read-resource [resource]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info "vfx fx catalog resource not found" {:resource resource})))
    (binding [*read-eval* false]
      (read-string (slurp url)))))

(defn- validate-manifest [manifest]
  (when-not (= 1 (:schema-version manifest))
    (throw (ex-info "unsupported vfx fx catalog schema version"
                    {:schema-version (:schema-version manifest)})))
  (let [documents (:documents manifest)]
    (when-not (vector? documents)
      (throw (ex-info "vfx fx manifest documents must be a vector" {})))
    (when-not (= (count documents) (count (set (map :id documents))))
      (throw (ex-info "vfx fx manifest contains duplicate ids" {}))))
  manifest)

(defn- user-types
  "The union of every {:type t} declared across a doc's :inputs :spawn/
   :update maps (deduped by field name) -- the real per-frame capability
   surface a scene sample reads, since a live instance's :user carries
   whichever fields were last set at :spawn/:snapshot and merged by
   :update. Same computation cn.li.ac.vfx.fx-test's own input-types
   helper already proved out per-file; duplicated here as production
   code, not required from the test namespace."
  [doc]
  (let [normalize (fn [v] (if (map? v) (:type v) v))]
    (into {} (map (fn [[k v]] [k (normalize v)]))
          (merge (get-in doc [:inputs :spawn]) (get-in doc [:inputs :update])))))

(defn assemble
  "-> {effect-id {:scene dsl-text-or-nil :user-types {...} :emitters [...]
   :lifecycle kw}} -- the exact registry shape the new engine's own
   create-runtime function takes (cn.li.ability.client-vfx-v2/create-
   runtime's own :catalog-compile option)."
  ([] (assemble {}))
  ([{:keys [manifest] :or {manifest "ac/vfx/fx/manifest.edn"}}]
   (let [manifest-doc (validate-manifest (read-resource manifest))]
     (into {}
           (map (fn [{:keys [id resource kind]}]
                  (when-not (= :vfx/system kind)
                    (throw (ex-info "vfx fx manifest contains non-vfx/system"
                                    {:id id :kind kind})))
                  (let [doc (read-resource resource)]
                    (when-not (= id (:id doc))
                      (throw (ex-info "vfx fx source id mismatch"
                                      {:manifest-id id :source-id (:id doc)})))
                    [id {:scene (:scene doc)
                        :user-types (user-types doc)
                        :emitters (:emitters doc)
                        :lifecycle (:lifecycle doc)}])))
           (:documents manifest-doc)))))
