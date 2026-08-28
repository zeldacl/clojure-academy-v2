(ns cn.li.ac.ability.service.combat-catalog
  "Read-only projection of the single final catalog.
   This service contains no migration status, evaluator fallback or skill-specific
   suffix logic; all behavior and passive effects come from the compiled EDN.")

(defonce ^:private state* (atom {:status :cold}))
(defn- final-var [symbol]
  (or (requiring-resolve symbol)
      (throw (ex-info "final catalog service unavailable" {:symbol symbol}))))

(defn- source-map [assembled] (get-in assembled [:combat :sources] {}))
(defn- registration-map [assembled]
  (into {} (map (juxt :id identity)) (get-in assembled [:combat :registrations])))

(defn- ability-map [assembled]
  (into {}
        (map (fn [[id entry]]
               (let [source (get (source-map assembled) (:source-id entry) {})
                     bindings (:bindings entry)]
                 [id (merge source
                            (or (:metadata bindings) {})
                            {:id id
                             :source-id (:source-id entry)
                             :bindings bindings
                             :presentation (:presentation bindings)
                             :program (:compiled entry)})])))
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
        value (assoc assembled :status :ready :combat combat)]
    (reset! state* value)
    value))

(defn state [] @state*)
(defn catalog [] @state*)
(defn available? [ability-id]
  (contains? (get-in @state* [:combat :abilities]) ability-id))
(defn ui-state [ability-id]
  {:ability-id ability-id :available? (available? ability-id) :enabled? (available? ability-id)})

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
  (or (get-in @state* [:combat :abilities ability-id])
      (throw (ex-info "ability is unavailable" {:reason :ability-unavailable :ability-id ability-id}))))

(defn apply-passive-resource-modifiers
  "Apply passive effects declared by learned course registrations.
   The reducer is generic over EDN `:passive-effects` and has no skill-name
   knowledge, so adding a course does not require code changes."
  [ability-data values]
  (let [learned (set (or (:learned-skills ability-data) #{}))
        abilities (get-in @state* [:combat :abilities])]
    (reduce (fn [result skill-id]
              (reduce (fn [acc {:keys [target operation value]}]
                        (case operation
                          :add (update acc target (fnil + 0.0) (double value))
                          :multiply (update acc target (fnil * 1.0) (double value))
                          :set (assoc acc target value)
                          acc))
                      result
                      (get-in abilities [skill-id :passive-effects])))
            values learned)))

(defn- normalize-translations [translations]
  (into {}
        (map (fn [[locale entries]]
               [locale (into {} (map (fn [[key value]]
                                       [(if (keyword? key) (name key) (str key)) value])
                                     entries))]))
        (or translations {})))

(defn skill-specs []
  (mapv (fn [[ability-id ability]]
          {:id ability-id :category-id (or (:category-id ability) :generic)
           :level (or (:level ability) 1) :controllable? (:controllable? ability)
           :name-key (:name-key ability) :description-key (:description-key ability)
           :icon (:icon ability) :ctrl-id (or (:ctrl-id ability) ability-id)
           :pattern (or (:pattern ability) :passive) :actions (or (:actions ability) {})
           :translations (normalize-translations (:translations ability))
           :cooldown {:mode :default} :execution :final})
        (sort-by first (get-in @state* [:combat :abilities]))))


