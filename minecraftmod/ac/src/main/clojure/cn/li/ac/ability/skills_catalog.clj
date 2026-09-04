(ns cn.li.ac.ability.skills-catalog
  "S8 cutover: AC content assembly for the NEW node-core engine, parallel
   to cn.li.ac.ability.final-catalog-service (the old engine's loader).

   Reads ac/skills/manifest.edn (an exact copy of ac/combat/manifest.edn
   with only the :resource paths repointed at ac/skills/*.edn -- the same
   50 registrations sharing 39 sources, same :source-id indirection, same
   per-registration :bindings, since S6 kept every ac/skills/*.edn file's
   non-:program top-level keys byte-for-byte identical to its
   ac/combat/abilities/*.edn counterpart). Unlike the old loader, :program
   is already new-DSL TEXT (a string), not a tree needing composite
   expansion -- the new engine's own compile-doc! (via cn.li.combat.api)
   reads it directly.

   Compiles to IR only (host-independent) -- cn.li.ability.engine-v2's own
   initialize! does the final compile-program step once it has built the
   shared host, mirroring how the old loader's :node-environment (also
   host-independent) gets bound to a real host only inside create-engine."
  (:require [clojure.java.io :as io]
            [cn.li.combat.api :as combat-api]))

(defn- read-resource [resource]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info "skills catalog resource not found" {:resource resource})))
    (binding [*read-eval* false]
      (read-string (slurp url)))))

(defn- validate-manifest [manifest]
  (when-not (= 1 (:schema-version manifest))
    (throw (ex-info "unsupported skills catalog schema version"
                    {:schema-version (:schema-version manifest)})))
  (let [documents (:documents manifest)]
    (when-not (vector? documents)
      (throw (ex-info "skills manifest documents must be a vector" {})))
    (when-not (= (count documents) (count (set (map :id documents))))
      (throw (ex-info "skills manifest contains duplicate ids" {}))))
  manifest)

(defn assemble
  "-> {:sources {source-id raw-doc} :registrations [{:id :source-id
   :bindings :ir} ...] :by-id {registration-id registration}}. raw-doc
   keeps every top-level key from its ac/skills/*.edn file, including the
   still-string :program (callers needing the compiled IR read
   :ir off the REGISTRATION, not the source, matching the old loader's
   own registration/source split)."
  ([] (assemble {}))
  ([{:keys [manifest] :or {manifest "ac/skills/manifest.edn"}}]
   (let [manifest-doc (validate-manifest (read-resource manifest))
         sources (reduce (fn [result {:keys [id resource kind source-id]}]
                           (when-not (= :ability kind)
                             (throw (ex-info "skills manifest contains non-ability"
                                             {:id id :kind kind})))
                           (let [source (read-resource resource)
                                 source-key (or source-id id)]
                             (when-not (or (= source-key (:id source)) (= id (:id source)))
                               (throw (ex-info "skills source id mismatch"
                                               {:manifest-id id :source-key source-key
                                                :source-id (:id source)})))
                             (assoc result source-key source)))
                         {} (:documents manifest-doc))
         ir-by-source (into {}
                            (map (fn [[source-key source]]
                                  [source-key (combat-api/compile-skill-doc! (:program source))]))
                            sources)
         registrations (mapv (fn [{:keys [id source-id bindings]}]
                               (let [source-key (or source-id id)]
                                 (when-not (get sources source-key)
                                   (throw (ex-info "skills registration source missing"
                                                   {:registration id :source-id source-key})))
                                 {:id id
                                  :source-id source-key
                                  :bindings (or bindings {})
                                  :ir (get ir-by-source source-key)}))
                             (:documents manifest-doc))
         by-id (into {} (map (juxt :id identity)) registrations)]
     {:sources sources
      :registrations registrations
      :by-id by-id
      :source-count (count sources)
      :registration-count (count registrations)})))
