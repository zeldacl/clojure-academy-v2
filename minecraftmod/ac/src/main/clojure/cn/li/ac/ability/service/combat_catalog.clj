(ns cn.li.ac.ability.service.combat-catalog
  "Read-only metadata projection for skill UI, trigger resolution and
   passive effects. The V3 document is the single source of truth; no
   manifest/source indirection is reconstructed here."
  (:require [cn.li.ac.ability.skills-catalog :as skills-catalog]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.node.digest :as digest]))

(defonce ^:private state* (atom {:status :cold}))
(def ^:const schema-version 1)

(defn- source-map [assembled] (:sources assembled {}))
(defn- registration-map [assembled] (:by-id assembled {}))

(defn- ability-map [assembled]
  (into {}
        (map (fn [[id entry]]
               (let [document (:document entry)
                     bindings (:bindings entry)
                     source (or document
                                (get (source-map assembled) (:source-id entry))
                                {})
                     metadata (or (:metadata document) (:metadata bindings) {})
                     presentation (if (contains? document :presentation)
                                    (:presentation document)
                                    (:presentation bindings))]
                 [id (merge source
                            metadata
                            {:id id
                             :bindings (or bindings {})
                             :presentation presentation
                             :program (:ir entry)})])))
        (registration-map assembled)))

(defn- content-hash [assembled]
  (digest/content-hash
   {:sources (source-map assembled)
    :registrations (mapv #(dissoc % :ir) (:registrations assembled))}))

(defn initialize! []
  (let [assembled (skills-catalog/assemble)
        abilities (ability-map assembled)
        trigger-index (reduce (fn [index source]
                                (reduce (fn [result trigger]
                                          (if (and (:source trigger) (:dispatch trigger))
                                            (update result (:source trigger) (fnil conj []) trigger)
                                            result))
                                        index (:external-triggers source)))
                              {} (vals (source-map assembled)))
        combat {:sources (source-map assembled)
                :registrations (:registrations assembled)
                :abilities abilities
                :by-id (registration-map assembled)
                :trigger-index trigger-index
                :errors {}}
        value {:status :ready
               :schema-version schema-version
               :content-hash (content-hash assembled)
               :combat combat}]
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

(defn apply-passive-resource-modifiers [ability-data values]
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
          (let [config-def (get skill-config/skill-definitions-by-id ability-id)]
            {:id ability-id
             :category-id (or (:category-id config-def)
                              (:category-id ability)
                              (get-in ability [:skill :category])
                              :generic)
             :level (or (:level config-def) (:level ability) (get-in ability [:skill :level]) 1)
             :controllable? (if (contains? config-def :controllable?)
                              (:controllable? config-def)
                              (:controllable? ability))
             :name-key (:name-key ability) :description-key (:description-key ability)
             :icon (:icon ability) :ctrl-id (or (:ctrl-id ability) ability-id)
             :actions (or (:actions ability) {})
             :translations (normalize-translations (:translations ability))
             :cooldown {:mode :default} :execution :final}))
        (sort-by first (get-in @state* [:combat :abilities]))))
