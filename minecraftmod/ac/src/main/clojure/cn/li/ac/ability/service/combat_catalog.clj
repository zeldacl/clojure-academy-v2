(ns cn.li.ac.ability.service.combat-catalog
  "Compatibility-free AC view over the final typed catalog.

   The namespace name is retained because AC UI/config callers consume the
   catalog service, but no legacy recipe, VM, composite loader, or VFX loader
   is required. Every ability entry is a final compiled registration."
  (:require [cn.li.ac.ability.skill-config :as skill-config]))

(defonce ^:private state* (atom {:initialized? false :status :cold}))
(defn- final-var [symbol]
  (or (requiring-resolve symbol)
      (throw (ex-info "final catalog service unavailable" {:symbol symbol}))))
(defn- source-map [assembled]
  (get-in assembled [:combat :sources] {}))
(defn- registration-map [assembled]
  (into {} (map (juxt :id identity)) (get-in assembled [:combat :registrations])))
(defn- ability-map [assembled]
  (into {}
        (map (fn [[id entry]]
               (let [source (get (source-map assembled) (:source-id entry) {})
                     bindings (:bindings entry)
                     metadata (or (:metadata bindings) {})
                     ;; The final graph owns execution. This typed table is
                     ;; consulted only for progression/UI identity fields
                     ;; absent from a source graph; it never selects or
                     ;; evaluates a program.
                     progression (select-keys
                                  (get skill-config/skill-definitions-by-id id)
                                  [:category-id :level :controllable?])]
                 [id (merge source
                            ;; Registration metadata is part of the final
                            ;; catalog ABI.  It must be projected here before
                            ;; the AC progression/UI registry is populated;
                            ;; otherwise specialization entries lose their
                            ;; category/prerequisite identity at startup.
                            progression
                            metadata
                            {:id id :source-id (:source-id entry)
                             :bindings bindings
                             :presentation (:presentation bindings)
                             :program (:compiled entry)
                             ;; final-catalog-service aborts initialization
                             ;; unless every entry is ready, so a successful
                             ;; projection has one execution status only.
                             :status :migrated
                             :engine :final})])))
        (registration-map assembled)))

(defn initialize! []
  (let [assembled ((final-var 'cn.li.ac.ability.final-catalog-service/initialize!))
        abilities (ability-map assembled)
        trigger-index (reduce (fn [index source]
                                (reduce (fn [result trigger]
                                          (if (and (:source trigger) (:dispatch trigger))
                                            (update result (:source trigger) (fnil conj []) trigger)
                                            result))
                                        index (:external-triggers source)))
                              {} (vals (source-map assembled)))
        combat (assoc (:combat assembled)
                      :abilities abilities
                      :by-id (registration-map assembled)
                      :trigger-index trigger-index
                      :errors {})
        value {:initialized? true
               :status :ready
               :schema-version (:schema-version assembled)
               :migration (into {} (map (fn [[id ability]] [id (:status ability)])) abilities)
               :combat combat
               :vfx (:vfx assembled)
               :content-hash (:content-hash assembled)}]
    (reset! state* value)
    value))

(defn state [] @state*)
(defn catalog [] @state*)
(defn migration-status [ability-id]
  (get-in @state* [:migration ability-id] :pending))
(defn available? [ability-id]
  (and (= :migrated (migration-status ability-id))
       (contains? (get-in @state* [:combat :abilities]) ability-id)))
(defn ui-state [ability-id]
  {:ability-id ability-id :migrated? (available? ability-id)
   :enabled? (available? ability-id) :status (migration-status ability-id)})

(defn resolve-trigger [source facts]
  (some (fn [trigger]
          (let [filter (:filter trigger) dispatch (:dispatch trigger)
                item-id (:item-id facts)]
            (when (and (or (nil? (:item-ids filter))
                           (some #{item-id} (:item-ids filter)))
                       (or (not (contains? filter :ability-mode?))
                           (= (:ability-mode? filter) (:ability-mode? facts)))
                       (available? (:ability dispatch)))
              dispatch)))
        (get-in @state* [:combat :trigger-index source])))

(defn require-available [ability-id]
  (when-not (available? ability-id)
    (throw (ex-info "ability-not-migrated"
                    {:reason :ability-not-migrated :ability-id ability-id
                     :status (migration-status ability-id)})))
  (get-in @state* [:combat :abilities ability-id]))

(defn apply-passive-resource-modifiers
  "Apply the generic course modifiers owned by AC's ability data.

  Course registrations are shared final graphs, but their resource effects
  are player-local progression rules. Keeping this reducer pure avoids a
  second evaluator and makes the multiplayer boundary explicit: only the
  supplied owner's immutable `:learned-skills` set is inspected.
  "
  [ability-data values]
  (let [learned (set (or (:learned-skills ability-data) #{}))
        has-suffix? (fn [suffix]
                      (boolean
                       (some (fn [skill-id]
                               (and (keyword? skill-id) (= suffix (name skill-id))))
                             learned)))
        cp-speed (double (or (:cp-recovery-speed values) 0.0))]
    (cond-> values
      (has-suffix? "brain-course")
      (update :max-cp (fnil + 0.0) 1000.0)

      (has-suffix? "brain-course-advanced")
      (-> (update :max-cp (fnil + 0.0) 1500.0)
          (update :max-overload (fnil + 0.0) 100.0))

      (has-suffix? "mind-course")
      (assoc :cp-recovery-speed (* cp-speed 1.2)))))

(defn- normalize-translations [translations]
  (into {}
        (map (fn [[locale entries]]
               [locale (into {} (map (fn [[key value]]
                                       [(if (keyword? key) (name key) (str key)) value])
                                     entries))]))
        (or translations {})))

(defn migrated-skill-specs []
  (mapv (fn [[ability-id ability]]
          (let [category-id (or (:category-id ability) :generic)]
            {:id ability-id :category-id category-id
             :level (:level ability)
             :controllable? (:controllable? ability)
             :name-key (:name-key ability)
             :description-key (:description-key ability)
             :icon (:icon ability)
             :ctrl-id (or (:ctrl-id ability) ability-id)
             :pattern (or (:pattern ability) :passive)
             :actions (or (:actions ability) {})
             :translations (normalize-translations (:translations ability))
             ;; Registry schema validates this as a presentation/progression
             ;; field; actual cooldown policy is owned by the final graph.
             :cooldown {:mode :default} :execution :final}))
        (sort-by first (filter (fn [[id _]] (available? id))
                               (get-in @state* [:combat :abilities])))))
