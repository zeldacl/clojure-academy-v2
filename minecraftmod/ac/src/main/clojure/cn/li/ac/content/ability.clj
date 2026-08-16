(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here.
  Skills are declared by discovered skill namespaces and registered only during
  explicit ability content initialization."
  (:require [cn.li.ac.ability.dsl :refer [defcategory]]
            [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.service.combat-content :as combat-content]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.spi-lifecycle :as lifecycle]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.discovery.core :as discovery-core]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.content.ability.teleporter.passive-hooks :as tp-passive]
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

(defn- load-discovered-skill-namespaces!
  "Require every discovered skill namespace, returning them as loadable
  namespace symbols (discovery hands them over split as ns/name)."
  []
  (let [skill-namespaces (mapv discovery-core/ns-symbol
                               (discovery/discovered-skill-namespaces))]
    (doseq [ns-sym skill-namespaces]
      (require ns-sym))
    skill-namespaces))

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

(defn- run-namespace-init!
  [ns-sym]
  ;; Combat Core owns authoritative combat lifecycle and damage semantics.
  ;; These namespaces remain content declarations, but their old Context-era
  ;; listeners must never be installed.
  (when-not (contains? #{'cn.li.ac.content.ability.vecmanip.vec-reflection
                        'cn.li.ac.content.ability.vecmanip.vec-deviation}
                       ns-sym)
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
              (throw e)))))))))

(defn init-ability-content!
  []
  (install/framework-once! ::ability-content-installed
    (fn []
      ;; The executable Combat Core catalog is now the completeness gate for
      ;; all 38 configured skills. Legacy namespaces may not add a skill that
      ;; is absent from this catalog.
      (combat-content/assert-complete-skill-catalog!)
      (doseq [cat [electromaster meltdowner-category teleporter vecmanip]]
        (category/register-category! (dissoc cat :ac/content-type)))
      (let [skill-namespaces (load-discovered-skill-namespaces!)]
        (register-declared-skills! skill-namespaces)
        (doseq [ns-sym skill-namespaces]
          (run-namespace-init! ns-sym)))
      ;; Legacy AC skill metadata is initialized before its registry freezes.
      ;; Combat Core content is registered separately by its composition root.
      (md-damage/init!)
      (tp-passive/register-passive-hooks!)
      ;; Register generic item actions (not skill-specific)
      (item-actions/register-item-action! "ac:app_skill_tree" :open-skill-tree)
      (discovery/freeze-provider-discovery!)
      (category/freeze-category-registry!)
      (skill-registry/freeze-skill-registry!)
      (item-actions/freeze-item-action-registries!)
      (damage-handler/freeze-attack-check-registries!)
      (damage-runtime/freeze-damage-handler-registry!)
      (passive/freeze-passive-handler-registry!)
      (lifecycle/freeze-lifecycle-registry!)
      (log/info "Ability content initialized")))
  nil)

(defn reset-ability-content-for-test!
  "Test-only: clear the ability-content install guard so init-ability-content!
   can rerun within the same Framework lifetime."
  []
  (install/reset-framework-once-flag-for-test! ::ability-content-installed)
  nil)
