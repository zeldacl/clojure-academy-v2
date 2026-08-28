(ns cn.li.ac.ability.final-catalog-service
  "Runtime-owned view of the final AC catalog.

   The catalog is assembled once and every registration must compile into the
   final graph IR. A non-executable registration aborts startup; there is no
   legacy evaluator or pending fallback."
  (:require [cn.li.node.schema-export :as schema]
            [cn.li.node.scope :as scope]
            [cn.li.node.validate :as validate]))

(defonce ^:private catalog-state (atom {:status :cold}))

(defn- catalog-api []
  (requiring-resolve 'cn.li.ac.ability.final-catalog/assemble))

(defn- compiler-api []
  (requiring-resolve 'cn.li.combat.final-compiler/compile-program))

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

(defn- compile-registration [environment registration]
  (try
    (assoc registration
           :status :ready
           :compiled ((compiler-api) environment (:graph registration)))
    (catch clojure.lang.ExceptionInfo error
      (throw (ex-info "final catalog graph compilation failed"
                      (merge {:reason :final-graph-compile-failed
                              :id (:id registration)}
                             (ex-data error)))))))

(defn initialize!
  "Load and index the immutable final catalog.

   `assemble-options` is forwarded to the pure catalog loader, which keeps
   this service usable in headless tests and deterministic server startup."
  ([] (initialize! {}))
  ([assemble-options]
   (let [assembled ((catalog-api) assemble-options)
         node-schema (strict-graphs! assembled)
         registrations (mapv #(compile-registration (:node-environment assembled) %)
                             (get-in assembled [:combat :registrations]))
         by-id (into {} (map (juxt :id identity) registrations))
         result (assoc assembled
                       :combat (assoc (:combat assembled)
                                      :registrations registrations
                                      :by-id by-id)
                       :status :ready
                       :ready-count (count (filter #(= :ready (:status %)) registrations))
                       :pending-count 0)]
     (let [result (assoc result :node-schema node-schema)]
       (reset! catalog-state result)
       result))))

(defn state [] @catalog-state)

(defn registration [id]
  (get-in @catalog-state [:combat :by-id id]))

(defn available? [id]
  (= :ready (:status (registration id))))

(defn program [id]
  (:compiled (registration id)))

(defn vfx-catalog []
  (get-in @catalog-state [:vfx :effects]))

(defn content-hash []
  (:content-hash @catalog-state))

(defn migration-status []
  (select-keys @catalog-state [:status :ready-count :pending-count :content-hash]))

(defn migration-report
  "Return a deterministic audit of every final registration."
  []
  (let [registrations (get-in @catalog-state [:combat :registrations])]
    {:total (count registrations)
     :ready (count (filter #(= :ready (:status %)) registrations))
     :pending 0
     :entries (mapv (fn [entry]
                      (select-keys entry [:id :source-id :status :compile-error]))
                    (sort-by (comp str :id) registrations))}))




