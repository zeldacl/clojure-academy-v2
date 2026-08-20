(ns cn.li.ac.ability.service.edn-catalog
  "Authoritative first-phase EDN catalog. Unmigrated skills have no runtime fallback."
  (:require [cn.li.combat.recipe :as combat-recipe]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.vfx.recipe :as vfx-recipe]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private state*
  (atom {:initialized? false
         :migration {}
         :combat nil
         :vfx nil
         :trigger-index {}}))

(defn- build-trigger-index [abilities]
  (reduce (fn [index ability]
            (reduce (fn [acc trigger]
                      (let [source (:source trigger)
                            dispatch (:dispatch trigger)]
                        (if (and source (map? dispatch))
                          (update acc source (fnil conj [])
                                  {:filter (:filter trigger)
                                   :dispatch dispatch})
                          acc)))
                    index (:external-triggers ability)))
          {} abilities))

(defn- load-combat-document [resource]
  (let [document (safe-edn/read-resource! resource)]
    (if (= :ability (:kind document))
      (-> document
          skill-config/overlay-edn-parameters
          skill-config/overlay-edn-tunables)
      document)))

(defn initialize! []
  (let [migration (safe-edn/read-resource! "ac/ability/migration_status.edn")
        combat (combat-recipe/load-catalog!
                 {:manifest-resource "ac/combat/manifest.edn"
                  :composites-manifest-resource
                  "ac/combat/components_manifest.edn"
                  :document-loader load-combat-document})
        vfx (vfx-recipe/load-catalog!
              {:manifest-resource "ac/vfx/manifest.edn"
               :composites-manifest-resource
               "ac/vfx/components_manifest.edn"})]
    ;; A single ability failing to compile (bad EDN, a dataflow violation,
    ;; ...) does not fail the whole catalog load -- combat-recipe/load-catalog!
    ;; already dropped it from :abilities and carries the reason here so it
    ;; is loud, not silent, while every other ability still boots normally.
    (doseq [[ability-id error] (:errors combat)]
      (log/error "EDN ability" ability-id "failed to compile and is disabled:"
                 (:message error) (:data error)))
    (reset! state* {:initialized? true
                    :migration (:skills migration)
                    :combat combat
                    :vfx vfx
                    :trigger-index (build-trigger-index
                                     (vals (:abilities combat)))})
    @state*))

(defn state [] @state*)
(defn catalog [] @state*)

(defn migration-status [ability-id]
  (get-in @state* [:migration ability-id] :pending))

(defn available? [ability-id]
  (and (= :migrated (migration-status ability-id))
       (contains? (get-in @state* [:combat :abilities]) ability-id)))

(defn ui-state [ability-id]
  {:ability-id ability-id
   :migrated? (available? ability-id)
   :enabled? (available? ability-id)
   :status (migration-status ability-id)})

(defn resolve-trigger
  "Resolve a server-side external trigger without accepting client mappings."
  [source facts]
  (some (fn [{:keys [filter dispatch]}]
          (let [item-ids (:item-ids filter)
                item-id (:item-id facts)]
            (when (and (or (nil? item-ids) (some #{item-id} item-ids))
                       (or (not (contains? filter :ability-mode?))
                           (= (:ability-mode? filter)
                              (:ability-mode? facts)))
                       (available? (:ability dispatch)))
              dispatch)))
        (get-in @state* [:trigger-index source])))

(defn require-available [ability-id]
  (when-not (available? ability-id)
    (throw (ex-info "ability-not-migrated"
                    {:reason :ability-not-migrated
                     :ability-id ability-id
                     :status (migration-status ability-id)})))
  (get-in @state* [:combat :abilities ability-id]))

(defn migrated-skill-specs
  "Player-facing metadata derived from AC config plus migration state.

  This intentionally does not load the legacy combat provider.  A pending
  skill has no registry entry and is represented by `ui-state` instead."
  []
  (mapv (fn [ability-id]
          (let [{:keys [category-id level controllable?]}
                (get skill-config/skill-definitions-by-id ability-id)]
            {:id ability-id
             :category-id category-id
             :level level
             :controllable? controllable?
             :name-key (str "ability.skill." (name category-id) "." (name ability-id))
             :description-key (str "ability.skill." (name category-id) "." (name ability-id) ".desc")
             :icon (str "textures/abilities/" (name category-id) "/skills/" (name ability-id) ".png")
             :ctrl-id ability-id
             :pattern :hold-channel
             :actions {}
             ;; The registry field is metadata only; execution and cooldown
             ;; settlement remain in the EDN VM.  `:manual` keeps the
             ;; existing player-facing schema valid without installing a
             ;; legacy callback path.
             :cooldown {:mode :manual}
             :execution :edn}) )
        (sort (for [[ability-id status] (:migration @state*)
                    :when (and (= :migrated status) (available? ability-id))]
                ability-id))))
