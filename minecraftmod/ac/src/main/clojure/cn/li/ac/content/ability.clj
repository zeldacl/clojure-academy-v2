(ns cn.li.ac.content.ability
  "Ability content bootstrap.

  Categories are declared here. Executable skills come only from the
  authoritative EDN catalog; player-facing skill metadata is projected from
  the final catalog and never installed as an alternate execution provider."
  (:require [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.content.ability.teleporter.location-teleport-rpc :as loc-teleport-rpc]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]))

(defn- category-map [spec]
  (assoc spec :ac/content-type :category))

(def electromaster
  (category-map
   {:id :electromaster
    :name-key "ability.category.electromaster"
    :icon (modid/asset-path "textures" "guis/icons/icon_electromaster.png")
    :color [0.27 0.69 1.0 1.0]
    :prog-incr-rate 1.0
    :enabled true}))

(def meltdowner-category
  (category-map
   {:id :meltdowner
    :name-key "ability.category.meltdowner"
    :icon (modid/asset-path "textures" "guis/icons/icon_meltdowner.png")
    :color [0.1 1.0 0.3 1.0]
    :prog-incr-rate 1.0
    :enabled true}))

(def teleporter
  (category-map
   {:id :teleporter
    :name-key "ability.category.teleporter"
    :icon (modid/asset-path "textures" "guis/icons/icon_teleporter.png")
    :color [1.0 1.0 1.0 1.0]
    :prog-incr-rate 1.0
    :enabled true}))

(def vecmanip
  (category-map
   {:id :vecmanip
    :name-key "ability.category.vecmanip"
    :icon (modid/asset-path "textures" "guis/icons/icon_vecmanip.png")
    :color [0.0 0.0 0.0 1.0]
    :prog-incr-rate 1.0
    :enabled true}))

(defn register-combat-catalog!
  "Register player-facing metadata and initialize the authoritative EDN catalog.

   Pending skills remain metadata-only and are rejected by the server gate."
  []
  (combat-catalog/initialize!)
  (doseq [skill-spec (combat-catalog/migrated-skill-specs)]
    ;; Final entries enter the executable skill registry; the registry is a
    ;; UI/progression index and never an alternate graph evaluator.
    (skill-registry/register-skill! skill-spec))
  true)

(defn init-combat-ability-content!
  "Production composition root for ability content.

  Registers player-facing metadata from the final EDN catalog and installs no
  alternate Context or callback execution path."
  []
  (install/framework-once! ::combat-ability-content-installed
    (fn []
      (doseq [cat [electromaster meltdowner-category teleporter vecmanip]]
        (category/register-category! (dissoc cat :ac/content-type)))
      (register-combat-catalog!)
      ;; The saved-name screen is only a persistence/RPC bridge. Teleport
      ;; execution itself is the final Combat Core EDN program.
      (loc-teleport-rpc/init!)
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
