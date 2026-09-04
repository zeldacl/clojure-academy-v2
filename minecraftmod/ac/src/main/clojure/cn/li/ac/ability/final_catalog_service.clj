(ns cn.li.ac.ability.final-catalog-service
  "Runtime-owned view of the final AC catalog.

   The catalog is assembled once and every registration's raw graph is
   validated structurally (scope/type-shape, against the vocabulary
   environment) at startup; a malformed registration still aborts startup.

   No longer compiles each registration through combat-api/compile-program
   (the old engine's own compiler): nothing has executed a :compiled graph
   produced here since the old engine's own real dispatch entry point was
   deleted -- this service's only remaining real consumer, cn.li.ac.
   ability.service.combat-catalog, only ever reads registration/source
   metadata (name-key, actions, passive-effects, trigger index), never
   :compiled or :program. Compiling a graph nothing runs was pure wasted
   work standing between real content changes and the old compiler staying
   a hard dependency for no benefit -- strict-graphs!'s own validate!/
   check-in-environment! already catch structurally invalid content
   without it."
  (:require [cn.li.node.schema-export :as schema]
            [cn.li.node.scope :as scope]
            [cn.li.node.validate :as validate]
            [cn.li.ac.ability.final-catalog :as final-catalog]))

(defonce ^:private catalog-state (atom {:status :cold}))

(def ^:private forbidden-components #{:session/patch :txn/atomic :guard/resource})
(defn- legacy-form [value path]
  (cond
    (map? value)
    (let [component (:component value) reference (:ref value)]
      (or (when (and (contains? value :from) (keyword? (:from value)))
            {:path path :reason :legacy-from})
          (when (or (contains? value :tunable) (contains? value :invariant))
            {:path path :reason :legacy-binding})
          (when (and (vector? reference)
                     (contains? #{:slot :context :request :param} (first reference)))
            {:path path :reason :legacy-ref})
          (when (contains? forbidden-components component)
            {:path path :reason :legacy-component :component component})
          (some identity (map (fn [[key child]]
                                (legacy-form child (conj path key))) value))))
    (sequential? value)
    (some identity (map-indexed (fn [index child]
                                 (legacy-form child (conj path index))) value))
    :else nil))

(defn- strict-graphs! [assembled]
  (let [environment (:node-environment assembled)
        registration-programs (vec (keep :graph (get-in assembled [:combat :registrations])))
        source-programs (vec (keep #(or (:program %) (:graph %))
                                  (vals (get-in assembled [:combat :sources]))))
        vfx-programs (vec (keep :source-graph
                                (vals (get-in assembled [:vfx :effects]))))
        programs (vec (concat registration-programs source-programs vfx-programs))
        legacy (some (fn [program] (legacy-form program [:program])) programs)]
    (when legacy
      (throw (ex-info "assembled final graph contains legacy syntax" legacy)))
    ;; Validate every expanded combat source and registration, not just the
    ;; player-facing registration copy.  Scope must start empty: seeding it
    ;; with every local name found anywhere in the tree would make the check
    ;; vacuous and allow an unbound local to reach the runtime.
    (doseq [program (concat registration-programs source-programs)]
      (validate/validate-in-environment! environment program)
      (scope/check-in-environment! environment program))
    {:descriptor-count (count (schema/export-environment environment))
     :schema (schema/export-environment environment)}))

(defn initialize!
  "Load, structurally validate, and index the immutable final catalog."
  ([] (initialize! {}))
  ([assemble-options]
   (let [assembled (final-catalog/assemble assemble-options)
         node-schema (strict-graphs! assembled)
         registrations (get-in assembled [:combat :registrations])
         by-id (into {} (map (juxt :id identity) registrations))
         result (assoc assembled
                       :combat (assoc (:combat assembled)
                                      :registrations registrations
                                      :by-id by-id)
                       :status :ready)]
     (let [result (assoc result :node-schema node-schema)]
       (reset! catalog-state result)
       result))))

(defn state [] @catalog-state)

(defn registration [id]
  (get-in @catalog-state [:combat :by-id id]))

(defn available? [id]
  (some? (registration id)))

(defn vfx-catalog []
  (get-in @catalog-state [:vfx :effects]))

(defn content-hash []
  (:content-hash @catalog-state))

(defn catalog-status []
  (select-keys @catalog-state [:status :content-hash]))

(defn catalog-report
  "Return a deterministic audit of every registration's raw graph presence
   (post strict-graphs! validation -- see initialize!'s own docstring for
   why this no longer reports a :compiled count)."
  []
  (let [registrations (get-in @catalog-state [:combat :registrations])]
    {:total (count registrations)
     :graphed (count (filter :graph registrations))
     :entries (mapv (fn [entry]
                      (select-keys entry [:id :source-id :graph]))
                    (sort-by (comp str :id) registrations))}))
