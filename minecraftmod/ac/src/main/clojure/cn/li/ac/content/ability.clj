(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here. Executable skills are Combat Core's frozen
  catalog (see combat_content.clj); only the generic, non-combat progression
  course chain is still declared/discovered as content namespaces here."
  (:require [cn.li.ac.ability.dsl :refer [defcategory]]
            [cn.li.ac.ability.service.combat-content :as combat-content]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.content.ability.teleporter.location-teleport :as loc-teleport]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]))

(defcategory electromaster
  :id :electromaster
  :name-key "ability.category.electromaster"
  :icon (modid/asset-path "textures" "guis/icons/icon_electromaster.png")
  :color [0.27 0.69 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory meltdowner-category
  :id :meltdowner
  :name-key "ability.category.meltdowner"
  :icon (modid/asset-path "textures" "guis/icons/icon_meltdowner.png")
  :color [0.1 1.0 0.3 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory teleporter
  :id :teleporter
  :name-key "ability.category.teleporter"
  :icon (modid/asset-path "textures" "guis/icons/icon_teleporter.png")
  :color [1.0 1.0 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory vecmanip
  :id :vecmanip
  :name-key "ability.category.vecmanip"
  :icon (modid/asset-path "textures" "guis/icons/icon_vecmanip.png")
  :color [0.0 0.0 0.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defn- skill-spec?
  [value]
  (and (map? value)
       (= :skill (:ac/content-type value))))

(defn- skill-specs-from-value
  [value]
  (cond
    (delay? value)
    (skill-specs-from-value @value)

    (skill-spec? value)
    [(dissoc value :ac/content-type)]

    (sequential? value)
    (->> value
         (filter skill-spec?)
         (map #(dissoc % :ac/content-type)))

    :else
    nil))

(defn- declared-skill-specs
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (keep #(when (bound? %) (var-get %)))
       (mapcat skill-specs-from-value)))

(defn- register-declared-skills!
  [skill-namespaces]
  (doseq [ns-sym skill-namespaces
          skill-spec (declared-skill-specs ns-sym)]
    (skill-registry/register-skill! skill-spec)))

(defn register-combat-catalog!
  "Register the complete player-facing skill catalog from Combat Core.

   This is intentionally separate from the legacy discovery bootstrap so it
   can be validated in isolation before the old namespace path is removed."
  []
  (combat-content/assert-complete-skill-catalog!)
  (doseq [skill-spec (combat-content/skill-specs)]
    (skill-registry/register-skill! skill-spec))
  (combat-content/register! combat-runtime/register-provider!)
  (combat-runtime/initialize!)
  true)

(declare run-namespace-init!)

(def ^:private generic-content-namespaces
  '[cn.li.ac.content.ability.generic.brain-course
    cn.li.ac.content.ability.generic.brain-course-advanced
    cn.li.ac.content.ability.generic.course-chain
    cn.li.ac.content.ability.generic.mind-course])

(defn- register-generic-content!
  "Load and register the non-combat course chain explicitly.

  Generic courses are progression metadata, not executable combat skills, so
  they remain outside Combat Core while retaining their existing registry
  semantics."
  []
  (doseq [ns-sym generic-content-namespaces]
    (require ns-sym))
  (register-declared-skills! generic-content-namespaces)
  (doseq [ns-sym generic-content-namespaces]
    (run-namespace-init! ns-sym)))

(defn- run-namespace-init!
  [ns-sym]
  (when-let [init-var (ns-resolve ns-sym 'init!)]
    (when-let [init-fn (and (bound? init-var) (var-get init-var))]
      (when (ifn? init-fn)
        (try
          (init-fn)
          (catch clojure.lang.ExceptionInfo e
            (if (= "Conflicting network handler id" (ex-message e))
              ;; Re-initialization may revisit namespaces that already registered RPC handlers.
              ;; Keep existing handlers and continue rebuilding content registries.
              (log/debug "Skipped duplicate network handler during ability reinit"
                         {:namespace ns-sym :data (ex-data e)})
              (throw e))))))))

(defn init-combat-ability-content!
  "Production composition root for ability content.

  Registers all executable skills from the frozen Combat Core catalog and
  keeps only the generic course metadata on the AC side. Legacy skill
  discovery/Context execution has been removed entirely — this is the only
  composition root."
  []
  (install/framework-once! ::combat-ability-content-installed
    (fn []
      (combat-content/assert-complete-composition!)
      (doseq [cat [electromaster meltdowner-category teleporter vecmanip]]
        (category/register-category! (dissoc cat :ac/content-type)))
      (register-combat-catalog!)
      (register-generic-content!)
      ;; Domain-event/RPC bridges, not legacy skill discovery: damage-helper
      ;; writes radiation marks through combat-runtime's domain reducer, and
      ;; location-teleport registers the saved-location query/save/delete/
      ;; perform RPC handlers (Combat Core's own :location-teleport program
      ;; only covers the teleport-execution step, not location CRUD). Both
      ;; are required content regardless of which composition root is active.
      (md-damage/init!)
      (loc-teleport/init!)
      (item-actions/register-item-action! "ac:app_skill_tree" :open-skill-tree)
      (category/freeze-category-registry!)
      (skill-registry/freeze-skill-registry!)
      (item-actions/freeze-item-action-registries!)
      (log/info "Combat Core ability content initialized")))
  nil)

(defn reset-ability-content-for-test!
  "Test-only: clear the ability-content install guard so
   init-combat-ability-content! can rerun within the same Framework
   lifetime."
  []
  (install/reset-framework-once-flag-for-test! ::combat-ability-content-installed)
  nil)
