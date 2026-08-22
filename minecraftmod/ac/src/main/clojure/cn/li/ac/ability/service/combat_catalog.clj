(ns cn.li.ac.ability.service.combat-catalog
  "Authoritative first-phase EDN catalog. Unmigrated skills have no runtime fallback."
  (:require [cn.li.combat.recipe :as combat-recipe]
            [cn.li.combat.passives :as combat-passives]
            [cn.li.vfx.install :as vfx-install]
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

(defn- materialize-combat-document [document]
  (if (= :ability (:kind document))
    (-> document
        skill-config/overlay-edn-parameters
        skill-config/overlay-edn-tunables)
    document))

(defn- vfx-contract-errors
  "Every ability whose compiled :program asks a VFX effect for a payload
   that effect's own :inputs doesn't support -- unknown fields, missing
   required ones, an effect-id nothing compiled, or a non-empty :destroy
   payload. combat-core only knows what its abilities send (recipe.clj's
   vfx-signal-requirements); vfx-core (cn.li.vfx.install/validate-
   requirements!) is the one that owns the contract those requests are
   judged against -- this function is pure wiring between the two."
  [combat vfx]
  (into {}
        (keep (fn [[ability-id ability]]
                (let [requirements (combat-recipe/vfx-signal-requirements ability)
                      failures (vfx-install/validate-requirements! vfx requirements)]
                  (when (seq failures)
                    [ability-id {:message "VFX contract violation"
                                 :data {:failures failures}}]))))
        (:abilities combat)))

(defn initialize! []
  (let [combat (combat-recipe/load-catalog!
                 {:manifest-resource "ac/combat/manifest.edn"
                  :composites-manifest-resource
                  "ac/combat/components_manifest.edn"
                  :document-transform materialize-combat-document})
        vfx (vfx-recipe/load-catalog!
              {:manifest-resource "ac/vfx/manifest.edn"
               :composites-manifest-resource
               "ac/vfx/components_manifest.edn"})
        ;; A VFX contract violation disables only the offending ability,
        ;; the same Design E fail-closed granularity as a compile error --
        ;; every other ability still loads.
        vfx-errors (vfx-contract-errors combat vfx)
        combat (-> combat
                   (update :abilities #(apply dissoc % (keys vfx-errors)))
                   (update :errors merge vfx-errors))
        ;; Migration status is each ability's own :status field, not a
        ;; separately-maintained file -- a second place to update, and one
        ;; that can drift from the EDN it's supposedly describing. An
        ;; ability absent here (no EDN file, or one that failed to compile
        ;; or failed its VFX contract and was dropped from :combat's
        ;; :abilities) falls through migration-status's own :pending
        ;; default below.
        migration (into {} (map (fn [[id ability]] [id (:status ability)]))
                        (:abilities combat))]
    ;; A single ability failing to compile (bad EDN, a dataflow violation,
    ;; ...) does not fail the whole catalog load -- combat-recipe/load-catalog!
    ;; already dropped it from :abilities and carries the reason here so it
    ;; is loud, not silent, while every other ability still boots normally.
    ;; vfx-recipe/load-catalog! carries the same per-effect isolation.
    (doseq [[ability-id error] (:errors combat)]
      (log/error "EDN ability" ability-id "failed to compile and is disabled:"
                 (:message error) (:data error)))
    (doseq [[effect-id error] (:errors vfx)]
      (log/error "EDN VFX effect" effect-id "failed to compile and is disabled:"
                 (:message error) (:data error)))
    (reset! state* {:initialized? true
                    :migration migration
                    :combat combat
                    :vfx vfx
                    :passive-index (combat-passives/build-index
                                    {:combat combat})
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

(def ^:private default-vfx-audience
  "A VFX effect document with no declared :audience broadcasts to every
   nearby client (default server view-distance-ish radius) -- Psi-style
   networking assumes visible-to-everyone unless a skill's own effect
   opts into :self (camera/screen-post-process effects that only make
   sense to the caster's own client)."
  {:scope :tracking :radius 96.0})

(defn vfx-effect-audience
  "The compiled VFX effect's own :audience declaration, or the tracking
   default. combat-core never sees this -- it is purely a network-routing
   concern AC resolves at publish time (see combat_runtime/publish-result!)."
  [effect-id]
  (or (get-in @state* [:vfx :effects effect-id :audience])
      default-vfx-audience))

(defn vfx-effect-lifecycle
  "The compiled VFX effect's :lifecycle (:transient, :session, :persistent,
   or :singleton). Used by combat_runtime's late-join replay: only
   :session/:persistent instances need their last :spawn signal resent to a
   player who newly enters tracking range -- a :transient effect has already
   finished playing by the time anyone could join late."
  [effect-id]
  (get-in @state* [:vfx :effects effect-id :lifecycle]))

(defn require-available [ability-id]
  (when-not (available? ability-id)
    (throw (ex-info "ability-not-migrated"
                    {:reason :ability-not-migrated
                     :ability-id ability-id
                     :status (migration-status ability-id)})))
  (get-in @state* [:combat :abilities ability-id]))

(defn- normalize-translations
  "Convert safe-EDN keyword message keys to the registry's string-key map."
  [translations]
  (into {}
        (map (fn [[locale entries]]
               [locale
                (into {}
                      (map (fn [[key value]]
                             [(if (keyword? key) (name key) (str key)) value])
                           entries))]))
        (or translations {})))

(defn apply-passive-resource-modifiers
  "Apply learned passive resource effects declared by the Combat Core catalog.

   AC supplies the neutral ability-data snapshot; the effect evaluator and
   all effect semantics remain in Combat Core."
  [ability-data values]
  (combat-passives/apply-resource-modifiers @state* ability-data values))

(defn migrated-skill-specs
  "Player-facing metadata derived from AC config plus migration state.

  This intentionally does not load the legacy combat provider.  A pending
  skill has no registry entry and is represented by `ui-state` instead."
  []
  (mapv (fn [ability-id]
          (let [ability (get-in @state* [:combat :abilities ability-id])
                configured (get skill-config/skill-definitions-by-id ability-id)
                category-id (or (:category-id ability) (:category-id configured))
                level (or (:level ability) (:level configured))
                controllable? (if (contains? ability :controllable?)
                                (:controllable? ability)
                                (:controllable? configured))
                pattern (or (:pattern ability) :hold-channel)]
            {:id ability-id
             :category-id category-id
             :level level
             :controllable? controllable?
             :name-key (or (:name-key ability)
                           (str "ability.skill." (name category-id) "." (name ability-id)))
             :description-key (or (:description-key ability)
                                  (str "ability.skill." (name category-id) "." (name ability-id) ".desc"))
             :icon (or (:icon ability)
                       (str "textures/abilities/" (name category-id) "/skills/" (name ability-id) ".png"))
             :ctrl-id (or (:ctrl-id ability) ability-id)
             :pattern pattern
             :actions (or (:actions ability) {})
             :translations (normalize-translations (:translations ability))
             ;; The registry field is metadata only; execution and cooldown
             ;; settlement remain in the EDN VM.  `:manual` keeps the
             ;; existing player-facing schema valid without installing a
             ;; legacy callback path.
             :cooldown {:mode :manual}
             :execution :edn}) )
        (sort (for [[ability-id status] (:migration @state*)
                    :when (and (= :migrated status) (available? ability-id))]
                ability-id))))
