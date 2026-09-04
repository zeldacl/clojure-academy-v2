(ns cn.li.ac.ability.service.combat-catalog
  "Read-only metadata projection for the pieces the new engine's own
   dispatch path (cn.li.ac.ability.skills-catalog + engine-v2, wired
   through combat-runtime's final-runtime-v2) never covers: skill tree UI
   (skill-specs), item-trigger resolution (resolve-trigger), passive skill
   effects (apply-passive-resource-modifiers) and per-registration
   bindings consumed by activation-context. This was pure metadata even
   before the S8 cutover -- see final-catalog-service's own docstring --
   so switching its source from cn.li.ac.ability.final-catalog-service
   (old catalog, ac/combat/abilities/*.edn) to cn.li.ac.ability.
   skills-catalog (new catalog, ac/skills/*.edn) changes nothing about
   dispatch, only where this metadata is read from. S6 kept every
   ac/skills/*.edn file's non-:program top-level key byte-for-byte
   identical to its ac/combat/abilities/*.edn counterpart, and
   ac/skills/manifest.edn is an exact copy of ac/combat/manifest.edn
   (only :resource paths repointed), so every :bindings/:metadata/
   :name-key/:icon/:actions/:controllable?/:external-triggers/
   :passive-effects/:translations value this namespace reads is
   unchanged."
  (:require [cn.li.ac.ability.skills-catalog :as skills-catalog]
            [cn.li.node.digest :as digest]))

(defonce ^:private state* (atom {:status :cold}))

(def ^:const schema-version 1)

(defn- source-map [assembled] (:sources assembled {}))
(defn- registration-map [assembled] (:by-id assembled {}))

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
                             :program (:ir entry)})])))
        (registration-map assembled)))

(defn- content-hash
  "Deterministic, cross-process identity over the shipped content only
   (source docs + registration bindings) -- not the compiled :ir, which
   digest/canonical is not obliged to render safely and which two
   independently-launched processes reading the same resources will
   always recompile identically anyway. Mirrors the old catalog's own
   per-domain content-hash (final-catalog.clj's load-combat)."
  [assembled]
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
           :actions (or (:actions ability) {})
           :translations (normalize-translations (:translations ability))
           :cooldown {:mode :default} :execution :final})
        (sort-by first (get-in @state* [:combat :abilities]))))


