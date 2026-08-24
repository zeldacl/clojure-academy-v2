(ns cn.li.ac.ability.service.combat-catalog
  "Compatibility-free AC view over the final typed catalog.

   The namespace name is retained because AC UI/config callers consume the
   catalog service, but no legacy recipe, VM, composite loader, or VFX loader
   is required. Every ability entry is a final compiled registration.")

(defonce ^:private state* (atom {:initialized? false :status :cold}))
(defn- final-var [symbol]
  (or (requiring-resolve symbol)
      (throw (ex-info "final catalog service unavailable" {:symbol symbol}))))
(defn- skill-definitions []
  (or (some-> (requiring-resolve 'cn.li.ac.ability.skill-config/skill-definitions-by-id) deref)
      {}))

(defn- source-map [assembled]
  (get-in assembled [:combat :sources] {}))
(defn- registration-map [assembled]
  (into {} (map (juxt :id identity)) (get-in assembled [:combat :registrations])))
(defn- ability-map [assembled]
  (into {}
        (map (fn [[id entry]]
               [id (merge (get (source-map assembled) (:source-id entry) {})
                          {:id id :source-id (:source-id entry)
                           :program (:compiled entry)
                           :status (if (= :ready (:status entry)) :migrated :pending)
                           :engine :final})]))
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

(defn apply-passive-resource-modifiers [_ability-data values]
  ;; Passive resource effects are represented as final policy data. Resource
  ;; settlement is performed by the final combat transaction; this catalog
  ;; view never executes a second evaluator.
  values)

(defn- normalize-translations [translations]
  (into {}
        (map (fn [[locale entries]]
               [locale (into {} (map (fn [[key value]]
                                       [(if (keyword? key) (name key) (str key)) value])
                                     entries))]))
        (or translations {})))

(defn migrated-skill-specs []
  (mapv (fn [[ability-id ability]]
          (let [configured (get (skill-definitions) ability-id)
                category-id (or (:category-id ability) (:category-id configured))]
            {:id ability-id :category-id category-id
             :level (or (:level ability) (:level configured))
             :controllable? (if (contains? ability :controllable?)
                              (:controllable? ability)
                              (:controllable? configured))
             :name-key (or (:name-key ability)
                           (str "ability.skill." (name category-id) "." (name ability-id)))
             :description-key (or (:description-key ability)
                                  (str "ability.skill." (name category-id) "."
                                       (name ability-id) ".desc"))
             :icon (or (:icon ability)
                       (str "textures/abilities/" (name category-id) "/skills/"
                            (name ability-id) ".png"))
             :ctrl-id (or (:ctrl-id ability) ability-id)
             :pattern (or (:pattern ability) :hold-channel)
             :actions (or (:actions ability) {})
             :translations (normalize-translations (:translations ability))
             :cooldown {:mode :final} :execution :final}))
        (sort-by first (filter (fn [[id _]] (available? id))
                               (get-in @state* [:combat :abilities])))))
