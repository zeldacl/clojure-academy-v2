(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here.
  Skills are declared by discovered skill namespaces and registered only during
  explicit ability content initialization."
  (:require [cn.li.ac.ability.dsl :refer [defcategory]]
            [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defcategory electromaster
  :id :electromaster
  :name-key "ability.category.electromaster"
  :icon "textures/abilities/electromaster/icon.png"
  :color [0.27 0.69 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory meltdowner-category
  :id :meltdowner
  :name-key "ability.category.meltdowner"
  :icon "textures/abilities/meltdowner/icon.png"
  :color [0.1 1.0 0.3 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory teleporter
  :id :teleporter
  :name-key "ability.category.teleporter"
  :icon "textures/abilities/teleporter/icon.png"
  :color [1.0 1.0 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory vecmanip
  :id :vecmanip
  :name-key "ability.category.vecmanip"
  :icon "textures/abilities/vecmanip/icon.png"
  :color [0.0 0.0 0.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defonce-guard ability-content-installed?)

(defn- load-discovered-skill-namespaces!
  []
  (let [skill-namespaces (discovery/discovered-skill-namespaces)]
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

(defn- run-namespace-init!
  [ns-sym]
  (when-let [init-var (ns-resolve ns-sym 'init!)]
    (when-let [init-fn (and (bound? init-var) (var-get init-var))]
      (when (ifn? init-fn)
        (init-fn)))))

(defn init-ability-content!
  []
  (with-init-guard ability-content-installed?
    (doseq [cat [electromaster meltdowner-category teleporter vecmanip]]
      (category/register-category! (dissoc cat :ac/content-type)))
    (effect/init-default-ops!)
    (let [skill-namespaces (load-discovered-skill-namespaces!)]
      (register-declared-skills! skill-namespaces)
      (doseq [ns-sym skill-namespaces]
        (run-namespace-init! ns-sym)))
    ;; Register generic item actions (not skill-specific)
    (item-actions/register-item-action! "ac:app_skill_tree" :open-skill-tree)
    (log/info "Ability content initialized")))
