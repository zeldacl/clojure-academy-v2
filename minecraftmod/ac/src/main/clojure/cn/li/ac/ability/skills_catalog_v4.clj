(ns cn.li.ac.ability.skills-catalog-v4
  "Directory-backed V4 skill catalog."
  (:require [clojure.java.io :as io]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.util.classpath-edn :as classpath-edn]
            [cn.li.ac.vfx.fx-catalog-v4 :as fx-catalog]
            [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.util.log :as log]
            [cn.li.node.api :as node-api]))

(defn- effect-input-specs
  "effect-id → VFX `:inputs` map, for compile-time spawn payload shape checks
   (`:map-keys`, literal `:type`)."
  []
  (into {}
        (map (fn [[id e]]
               [id (or (get-in e [:document :inputs])
                       (get-in e [:document :parameters])
                       {})])
             (:by-id (fx-catalog/assemble)))))

(defn- default-compile-opts []
  {:vocab combat-api/skill-vocab
   :capabilities combat-api/skill-capability-type
   :fns combat-api/skill-lib-fns})

(defn- skill-name [id]
  (if-let [n (namespace id)]
    (str n "__" (name id) ".edn")
    (str (name id) ".edn")))

(defn- candidates []
  (into ["catalog_smoke.edn"]
        (concat (map skill-name skill-config/all-skill-ids)
                (for [cat skill-config/category-ids
                      suffix ["brain-course" "brain-course-advanced" "mind-course"]]
                  (str (name cat) "__" suffix ".edn")))))

(defn resource-names [root]
  (classpath-edn/edn-resource-names
   root
   {:sentinels ["arc-gen.edn" "catalog_smoke.edn"]
    :candidates (candidates)}))

(defn- read-resource [r]
  (let [u (or (classpath-edn/find-resource r) (io/resource r))]
    (when-not u
      (throw (ex-info "V4 skill resource not found" {:resource r})))
    (binding [*read-eval* false]
      (read-string (slurp u)))))

(defn- read-skill [r]
  (let [d (read-resource r)]
    (node-api/validate-v4-document! d)
    (when-not (= :skill (node-api/v4-document-kind d))
      (throw (ex-info "V4 skill catalog contains a non-skill"
                      {:resource r :schema (:schema d)})))
    {:resource r :id (:id d) :document d}))

(defn assemble
  ([] (assemble {}))
   ([{:keys [resource-root compile-opts mode]
     :or {resource-root "ac/skills-v4"
          mode :throw}}]
   (let [base (or compile-opts (default-compile-opts))
         opts (if (contains? base :effect-inputs)
                base
                (assoc base :effect-inputs (effect-input-specs)))
         resources (resource-names resource-root)
         _ (when (empty? resources)
             (log/warn "Skill catalog enumerated no V4 documents"
                       {:resource-root resource-root}))
         skills (mapv (fn [r]
                        (let [{:keys [id document] :as s} (read-skill r)
                              {:keys [ir diagnostics]}
                              (node-api/compile-v4-skill-document! document opts mode)]
                          (assoc s
                                 :semantic-digest (node-api/v4-document-semantic-digest document)
                                 :ir ir
                                 :diagnostics diagnostics)))
                      resources)
         ids (map :id skills)]
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "V4 skill ids must be unique" {:ids ids})))
     (doseq [{:keys [id ir document]} skills]
       (let [triggers (:entry-triggers ir)
             ir-entries (set (keys (:entries ir)))
             doc-graphs (set (keys (or (:graphs document) (:entries document))))]
         (when-not (seq triggers)
           (throw (ex-info "V4 skill IR missing :entry-triggers"
                           {:id id :ir-entries (vec ir-entries)})))
         (when-not (= (set (keys triggers)) ir-entries)
           (throw (ex-info "V4 :entry-triggers keys must match IR :entries"
                           {:id id
                            :triggers (vec (keys triggers))
                            :ir-entries (vec ir-entries)})))
         (when (and (seq doc-graphs) (not= ir-entries doc-graphs))
           (throw (ex-info "V4 IR :entries keys must match document graphs"
                           {:id id
                            :ir-entries (vec ir-entries)
                            :document-graphs (vec doc-graphs)})))))
     {:skills skills
      :registrations skills
      :sources (into {} (map (juxt :id :document) skills))
      :by-id (into {} (map (juxt :id identity)) skills)
      :resource-count (count resources)
      :source-count (count resources)
      :registration-count (count skills)})))
