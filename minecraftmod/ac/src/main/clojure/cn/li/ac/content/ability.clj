(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here.
  Skills are self-registered by requiring discovered skill namespaces."
  (:require [cn.li.ac.ability.dsl :refer [defcategory]]
            [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.ability.registry.category :as category]
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

(defn- require-discovered-skills! []
  (discovery/bootstrap-default-providers!)
  (doseq [ns-sym (discovery/discovered-skill-namespaces)]
    (require ns-sym)))

(defonce ^:private discovered-skills-required?
  (delay
    (require-discovered-skills!)
    true))

(defn ensure-discovered-skills-required!
  "Preserve historical load semantics: requiring this namespace should make
  skill specs available even before the explicit content init phase runs."
  []
  @discovered-skills-required?)

(ensure-discovered-skills-required!)

(defn init-ability-content!
  []
  (with-init-guard ability-content-installed?
    (doseq [cat [electromaster meltdowner-category teleporter vecmanip]]
      (category/register-category! (dissoc cat :ac/content-type)))
    (ensure-discovered-skills-required!)
    ;; Register generic item actions (not skill-specific)
    (item-actions/register-item-action! "ac:app_skill_tree" :open-skill-tree)
    (log/info "Ability content initialized")))
