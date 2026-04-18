(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here.
  Skills are self-registered by requiring each skill namespace."
  (:require [cn.li.ac.ability.dsl :refer [defcategory]]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.content.ability.electromaster.body-intensify]
            [cn.li.ac.content.ability.electromaster.current-charging]
            [cn.li.ac.content.ability.electromaster.mag-manip]
            [cn.li.ac.content.ability.electromaster.mag-movement]
            [cn.li.ac.content.ability.electromaster.railgun]
            [cn.li.ac.content.ability.electromaster.thunder-bolt]
            [cn.li.ac.content.ability.electromaster.thunder-clap]
            [cn.li.ac.content.ability.meltdowner.meltdowner]
            [cn.li.ac.content.ability.electromaster.arc-gen]
            [cn.li.ac.content.ability.telekinesis.vec-manip]
            [cn.li.ac.content.ability.teleporter.location-teleport]
            [cn.li.ac.content.ability.teleporter.mark-teleport]
            [cn.li.ac.content.ability.vecmanip.blood-retrograde]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave]
            [cn.li.ac.content.ability.vecmanip.directed-shock]
            [cn.li.ac.content.ability.vecmanip.groundshock]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon]
            [cn.li.ac.content.ability.vecmanip.storm-wing]
            [cn.li.ac.content.ability.vecmanip.vec-accel]
            [cn.li.ac.content.ability.vecmanip.vec-deviation]
            [cn.li.ac.content.ability.vecmanip.vec-reflection]
            [cn.li.mcmod.util.log :as log]))

(defcategory electromaster
  :id :electromaster
  :name-key "ability.category.electromaster"
  :icon "textures/abilities/electromaster/icon.png"
  :color [0.27 0.69 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory telekinesis
  :id :telekinesis
  :name-key "ability.category.telekinesis"
  :icon "textures/abilities/generic/skills/mind_course.png"
  :color [0.92 0.73 0.27 1.0]
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

(defonce ^:private ability-content-installed? (atom false))

(defn init-ability-content!
  []
  (when (compare-and-set! ability-content-installed? false true)
    (doseq [cat [electromaster telekinesis meltdowner-category teleporter vecmanip]]
      (category/register-category! (dissoc cat :ac/content-type)))
    ;; Register generic item actions (not skill-specific)
    (item-actions/register-item-action! "ac:app_skill_tree" :open-skill-tree)
    (log/info "Ability content initialized")))
