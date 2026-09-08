(ns cn.li.ac.ability.skills-catalog-v3
  "Directory-backed AC V3 skill catalog.

   Every public skill is one EDN document. The loader deliberately has no
   manifest/source-id layer: it enumerates `resource-root` and uses the
   document's own `:id` as the stable registration key. Shared behavior is
   represented by module references in the document, not by duplicated
   registration metadata.

   Enumeration works from an exploded development classpath, a packaged jar,
   and Loom runClient `union:` URLs (directory `getResources` is empty there)."
  (:require [clojure.java.io :as io]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.util.classpath-edn :as classpath-edn]
            [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.util.log :as log]
            [cn.li.node.api :as node-api]))

(defn- default-compile-opts []
  {:vocab combat-api/skill-vocab
   :capabilities combat-api/skill-capability-type
   :fns combat-api/skill-lib-fns})

(defn- skill-edn-name [id]
  (if-let [ns (namespace id)]
    (str ns "__" (name id) ".edn")
    (str (name id) ".edn")))

(defn- skill-candidate-names []
  (into ["catalog_smoke.edn"]
        (concat (map skill-edn-name skill-config/all-skill-ids)
                (for [cat skill-config/category-ids
                      suffix ["brain-course" "brain-course-advanced" "mind-course"]]
                  (str (name cat) "__" suffix ".edn")))))

(defn resource-names
  "Return sorted EDN resource paths below `root`.

   `root` is a classpath path such as `ac/skills-v3`; callers should not pass
   a leading slash. Duplicate classpath roots are harmless because names are
   deduplicated before sorting."
  [root]
  (classpath-edn/edn-resource-names
   root
   {:sentinels ["arc-gen.edn" "catalog_smoke.edn"]
    :candidates (skill-candidate-names)}))

(defn- read-resource [resource]
  (let [url (or (classpath-edn/find-resource resource) (io/resource resource))]
    (when-not url
      (throw (ex-info "AC V3 resource not found" {:resource resource})))
    (binding [*read-eval* false]
      (read-string (slurp url)))))

(defn- read-skill! [resource]
  (let [document (read-resource resource)]
    (node-api/validate-document! document)
    (when-not (= :skill (node-api/document-kind document))
      (throw (ex-info "skill catalog contains a non-skill document"
                      {:resource resource :schema (:schema document)})))
    {:resource resource
     :id (:id document)
     :document document}))

(defn assemble
  "Load and compile every skill under a resource directory.

   Options:
   * `:resource-root` (default `ac/skills-v3`)
   * `:compile-opts` compiler vocabulary/capability/function tables
   * `:mode` `:throw` (default) or `:collect`

   Returns `{:skills [...] :by-id {...} :resource-count n}`. In collect mode
   each item also carries `:diagnostics`, while throw mode fails during
   assembly so an invalid shipped document cannot silently register."
  ([] (assemble {}))
  ([{:keys [resource-root compile-opts mode]
     :or {resource-root "ac/skills-v3"
          mode :throw}}]
   (let [compile-opts (or compile-opts (default-compile-opts))
         resources (resource-names resource-root)
         _ (when (empty? resources)
             (log/warn "Skill catalog enumerated no EDN documents"
                       {:resource-root resource-root
                        :sentinel-found? (boolean (classpath-edn/find-resource
                                                   (str resource-root "/arc-gen.edn")))}))
         skills (mapv (fn [resource]
                        (let [{:keys [id document] :as skill} (read-skill! resource)
                              {:keys [ir diagnostics]}
                              (node-api/compile-skill-document!
                               document compile-opts mode)]
                          (assoc skill
                                 :semantic-digest (node-api/document-semantic-digest document)
                                 :ir ir
                                 :diagnostics diagnostics)))
                      resources)
         ids (map :id skills)]
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "AC V3 skill ids must be unique" {:ids ids})))
     {:skills skills
      :registrations skills
      :sources (into {} (map (juxt :id :document) skills))
      :by-id (into {} (map (juxt :id identity)) skills)
      :resource-count (count resources)
      :source-count (count resources)
      :registration-count (count skills)})))


