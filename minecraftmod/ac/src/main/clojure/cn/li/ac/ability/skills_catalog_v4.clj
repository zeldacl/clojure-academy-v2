(ns cn.li.ac.ability.skills-catalog-v4
  "Directory-backed V4 skill catalog."
  (:require [clojure.java.io :as io]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.util.classpath-edn :as classpath-edn]
            [cn.li.ac.vfx.fx-catalog-v4 :as fx-catalog]
            [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.runtime.vfx-contract :as vfx-contract]
            [cn.li.mcmod.util.log :as log]
            [cn.li.node.api :as node-api]
            [cn.li.vfx.api :as vfx-api]))

(defn effect-input-specs
  "effect-id → VFX `:inputs` map, for compile-time spawn payload shape checks
   (`:map-keys`, literal `:type`, unknown field names).

   The universal capabilities every scene gets regardless of what it declared
   (:age/:progress/:seed/:source-player-id) are merged in via vfx-api's own
   scene-capabilities-for, NOT re-listed here: a payload may legitimately
   carry :seed even though no effect declares it, and duplicating that set
   would mean a new universal silently became an 'unknown field'. Merged as
   `{:type t}` specs so they read like any other declared input.

   Public, and takes the already-assembled catalog, because the node editor
   needs the identical map: it used to build its own and omitted
   :auto-provided?, so its diagnostics panel reported missing/nil
   required-input errors for the universals that the real build never
   produces. One builder is the only way the two stay honest -- the editor
   is supposed to preview the build's verdict, not approximate it."
  ([] (effect-input-specs (:by-id (fx-catalog/assemble))))
  ([by-id]
   (let [universal (into {} (map (fn [[k t]] [k {:type t :auto-provided? true}]))
                         (vfx-api/scene-capabilities-for {}))]
     (into {}
           (map (fn [[id e]]
                 (let [document (:document e)
                       used (into #{}
                                  (keep (fn [form]
                                          (when (and (map? form)
                                                     (= :context-ref (:type form)))
                                            (:key form))))
                                  (tree-seq coll? seq (:graphs document)))
                       declared (or (:inputs document) (:parameters document) {})]
                   [id (merge universal
                              (into {}
                                    (map (fn [[k spec]]
                                          [k (cond-> spec
                                               (and (contains? used k)
                                                    (not (contains? spec :default)))
                                               (assoc :required? true))]))
                                     declared))])))
           by-id))))

(defn- default-compile-opts []
  {:vocab combat-api/skill-vocab
   :capabilities combat-api/skill-capability-type
   :fns combat-api/skill-lib-fns
   ;; Gives a (:field query-result) read a real type instead of :any. Must
   ;; stay in step with the editor's own opts (node_editor_reactive/
   ;; mode-opts) -- a field typed one way here and another way there would
   ;; mean the editor's diagnostics panel disagrees with the build.
   :field-types combat-api/skill-field-types
   ;; The VFX signal ABI's own operation set, so a typo'd vfx! :operation
   ;; is a compile error instead of vfx-contract/signal throwing "unknown
   ;; VFX signal operation" at spawn time. Passed in rather than duplicated:
   ;; node-core must not depend on mcmod.
   :vfx-operations vfx-contract/signal-ops})

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
     ;; :throw mode already refused to produce an IR for any ERROR, so
     ;; anything still here is a warning -- provably wrong but non-fatal
     ;; (today: payload fields the target effect does not declare and will
     ;; silently ignore). Log them; they are invisible otherwise, which is
     ;; how they survived this long.
     (doseq [{:keys [id diagnostics]} skills
             {:keys [code message]} diagnostics]
       (log/warn "V4 skill compiled with a warning" {:id id :code code :message message}))
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
