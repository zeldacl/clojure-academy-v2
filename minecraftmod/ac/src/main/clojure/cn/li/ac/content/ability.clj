(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here. Executable skills come only from the
  authoritative EDN catalog; the legacy provider is retained as metadata for
  migration/UI discovery and is never installed as an execution provider."
  (:require [cn.li.ac.ability.dsl :refer [defcategory]]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.config.modid :as modid]
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

(defn register-combat-catalog!
  "Register player-facing metadata and initialize the authoritative EDN catalog.

   Pending skills remain metadata-only and are rejected by the server gate."
  []
  (combat-catalog/initialize!)
  (doseq [skill-spec (combat-catalog/migrated-skill-specs)]
    ;; Only migrated entries enter the executable skill registry.  Pending
    ;; entries are represented by combat-catalog/ui-state and never receive a
    ;; legacy callback or fallback registration.
    (skill-registry/register-skill! skill-spec))
  true)

(defn init-combat-ability-content!
  "Production composition root for ability content.

  Registers player-facing metadata, then installs only migrated EDN abilities.
  Pending skills remain visible but disabled and are rejected at the server
  boundary; legacy Context execution is not installed."
  []
  (install/framework-once! ::combat-ability-content-installed
    (fn []
      (doseq [cat [electromaster meltdowner-category teleporter vecmanip]]
        (category/register-category! (dissoc cat :ac/content-type)))
      (register-combat-catalog!)
      ;; Domain-event/RPC bridges, not legacy skill discovery: location-
      ;; teleport registers the saved-location query/save/delete/
      ;; perform RPC handlers (Combat Core's own :location-teleport program
      ;; only covers the teleport-execution step, not location CRUD). Both
      ;; are required content regardless of which composition root is active.
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
