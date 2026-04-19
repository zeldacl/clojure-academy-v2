(ns cn.li.ac.achievement.data
  "Achievement metadata (original AcademyCraft aligned ids/tree).

  This namespace is pure data:
  - no Minecraft/Forge imports
  - no platform behavior
  - safe for both runtime and datagen reads."
  (:require [clojure.string :as str]))

(def tabs
  [{:id :default
    :background "my_mod:textures/gui/advancements/bg_default.png"}
   {:id :electromaster
    :background "my_mod:textures/gui/advancements/bg_electromaster.png"}
   {:id :meltdowner
    :background "my_mod:textures/gui/advancements/bg_meltdowner.png"}
   {:id :teleporter
    :background "my_mod:textures/gui/advancements/bg_teleporter.png"}
   {:id :vecmanip
    :background "my_mod:textures/gui/advancements/bg_vecmanip.png"}])

(defn- text-key
  [id suffix]
  (str "advancement.my_mod." id suffix))

(defn- title-of
  [id]
  (-> id
      (str/replace "." " ")
      (str/replace "_" " ")
      (str/split #"\s+")
      (->> (map str/capitalize))
      (str/join " ")))

(defn- zh-title-of
  [id]
  (str "能力成就 " (str/replace id "." " · ")))

(defn- base-ach
  [{:keys [id tab parent icon frame criteria trigger-key]}
   {:keys [en-title en-desc zh-title zh-desc]}]
  {:id id
   :tab tab
   :parent parent
   :icon icon
   :frame (or frame :task)
   :hidden? false
   :criteria criteria
   :trigger-key trigger-key
   :translation
   {:en_us {(text-key id "") (or en-title (title-of id))
            (text-key id ".description") (or en-desc (str "Complete " (title-of id) "."))}
    :zh_cn {(text-key id "") (or zh-title (zh-title-of id))
            (text-key id ".description") (or zh-desc (str "完成成就：" (or zh-title (zh-title-of id))))}}})

(defn- custom-criterion
  [id]
  [{:type :custom
    :criterion-id id}])

(defn- inventory-criterion
  [item-id]
  [{:type :inventory-changed
    :items [item-id]}])

(def achievements
  [(base-ach {:id "phase_liquid" :tab :default :parent nil :icon "my_mod:phase_liquid"
              :criteria (inventory-criterion "my_mod:phase_liquid") :trigger-key nil}
             {:en-title "Harvest Phase Liquid" :en-desc "Obtain phase liquid."
              :zh-title "收获相位液" :zh-desc "获得相位液。"})
   (base-ach {:id "matrix1" :tab :default :parent "phase_liquid" :icon "my_mod:matrix"
              :criteria (inventory-criterion "my_mod:matrix") :trigger-key nil} {})
   (base-ach {:id "matrix2" :tab :default :parent "matrix1" :icon "my_mod:advanced_matrix"
              :criteria (inventory-criterion "my_mod:advanced_matrix") :trigger-key nil} {})
   (base-ach {:id "node" :tab :default :parent "phase_liquid" :icon "my_mod:node"
              :criteria (inventory-criterion "my_mod:node") :trigger-key nil} {})
   (base-ach {:id "developer1" :tab :default :parent "node" :icon "my_mod:developer_normal"
              :criteria (inventory-criterion "my_mod:developer_normal") :trigger-key nil} {})
   (base-ach {:id "developer2" :tab :default :parent "developer1" :icon "my_mod:developer_advanced"
              :criteria (inventory-criterion "my_mod:developer_advanced") :trigger-key nil} {})
   (base-ach {:id "developer3" :tab :default :parent "developer2" :icon "my_mod:developer_portable"
              :criteria (inventory-criterion "my_mod:developer_portable") :trigger-key nil} {})
   (base-ach {:id "phasegen" :tab :default :parent "phase_liquid" :icon "my_mod:phase_generator"
              :criteria (inventory-criterion "my_mod:phase_generator") :trigger-key nil} {})
   (base-ach {:id "solargen" :tab :default :parent "phasegen" :icon "my_mod:solar_generator"
              :criteria (inventory-criterion "my_mod:solar_generator") :trigger-key nil} {})
   (base-ach {:id "windgen" :tab :default :parent "solargen" :icon "my_mod:wind_generator"
              :criteria (inventory-criterion "my_mod:wind_generator") :trigger-key nil} {})
   (base-ach {:id "crystal" :tab :default :parent nil :icon "my_mod:crystal_low"
              :criteria (inventory-criterion "my_mod:crystal_low") :trigger-key nil} {})
   (base-ach {:id "terminal" :tab :default :parent nil :icon "my_mod:terminal_installer"
              :criteria (inventory-criterion "my_mod:terminal_installer") :trigger-key nil} {})

   ;; Electromaster
   (base-ach {:id "electromaster.lv1" :tab :electromaster :parent nil :icon "my_mod:arc_gen"
              :criteria (custom-criterion "electromaster.lv1")
              :trigger-key {:kind :level-change :category :electromaster :level 1}} {})
   (base-ach {:id "electromaster.lv2" :tab :electromaster :parent "electromaster.lv1" :icon "my_mod:arc_gen"
              :criteria (custom-criterion "electromaster.lv2")
              :trigger-key {:kind :level-change :category :electromaster :level 2}} {})
   (base-ach {:id "electromaster.lv3" :tab :electromaster :parent "electromaster.lv2" :icon "my_mod:railgun"
              :criteria (custom-criterion "electromaster.lv3")
              :trigger-key {:kind :level-change :category :electromaster :level 3}} {})
   (base-ach {:id "electromaster.lv4" :tab :electromaster :parent "electromaster.lv3" :icon "my_mod:thunder_bolt"
              :criteria (custom-criterion "electromaster.lv4")
              :trigger-key {:kind :level-change :category :electromaster :level 4}} {})
   (base-ach {:id "electromaster.lv5" :tab :electromaster :parent "electromaster.lv4" :icon "my_mod:thunder_clap"
              :criteria (custom-criterion "electromaster.lv5")
              :trigger-key {:kind :level-change :category :electromaster :level 5}} {})
   (base-ach {:id "electromaster.arc_gen" :tab :electromaster :parent "electromaster.lv1" :icon "my_mod:arc_gen"
              :criteria (custom-criterion "electromaster.arc_gen")
              :trigger-key {:kind :skill-perform :skill-id :arc-gen}} {})
   (base-ach {:id "electromaster.attack_creeper" :tab :electromaster :parent "electromaster.arc_gen" :icon "minecraft:creeper_head"
              :criteria (custom-criterion "electromaster.attack_creeper")
              :trigger-key {:kind :custom :event-id "electromaster.attack_creeper"}} {})
   (base-ach {:id "electromaster.mag_movement" :tab :electromaster :parent "electromaster.attack_creeper" :icon "my_mod:mag_movement"
              :criteria (custom-criterion "electromaster.mag_movement")
              :trigger-key {:kind :skill-perform :skill-id :mag-movement}} {})
   (base-ach {:id "electromaster.body_intensify" :tab :electromaster :parent "electromaster.mag_movement" :icon "my_mod:body_intensify"
              :criteria (custom-criterion "electromaster.body_intensify")
              :trigger-key {:kind :skill-learn :skill-id :body-intensify}} {})
   (base-ach {:id "electromaster.mine_detect" :tab :electromaster :parent "electromaster.body_intensify" :icon "my_mod:mine_detect"
              :criteria (custom-criterion "electromaster.mine_detect")
              :trigger-key {:kind :skill-perform :skill-id :mine-detect}} {})
   (base-ach {:id "electromaster.thunder_bolt" :tab :electromaster :parent "electromaster.mine_detect" :icon "my_mod:thunder_bolt"
              :criteria (custom-criterion "electromaster.thunder_bolt")
              :trigger-key {:kind :skill-perform :skill-id :thunder-bolt}} {})
   (base-ach {:id "electromaster.railgun" :tab :electromaster :parent "electromaster.thunder_bolt" :icon "my_mod:railgun"
              :criteria (custom-criterion "electromaster.railgun")
              :trigger-key {:kind :skill-perform :skill-id :railgun}} {})
   (base-ach {:id "electromaster.thunder_clap" :tab :electromaster :parent "electromaster.railgun" :icon "my_mod:thunder_clap"
              :criteria (custom-criterion "electromaster.thunder_clap")
              :trigger-key {:kind :skill-perform :skill-id :thunder-clap}} {})

   ;; Meltdowner
   (base-ach {:id "meltdowner.lv1" :tab :meltdowner :parent nil :icon "my_mod:meltdowner"
              :criteria (custom-criterion "meltdowner.lv1")
              :trigger-key {:kind :level-change :category :meltdowner :level 1}} {})
   (base-ach {:id "meltdowner.lv2" :tab :meltdowner :parent "meltdowner.lv1" :icon "my_mod:meltdowner"
              :criteria (custom-criterion "meltdowner.lv2")
              :trigger-key {:kind :level-change :category :meltdowner :level 2}} {})
   (base-ach {:id "meltdowner.lv3" :tab :meltdowner :parent "meltdowner.lv2" :icon "my_mod:mine_ray"
              :criteria (custom-criterion "meltdowner.lv3")
              :trigger-key {:kind :level-change :category :meltdowner :level 3}} {})
   (base-ach {:id "meltdowner.lv4" :tab :meltdowner :parent "meltdowner.lv3" :icon "my_mod:jet_engine"
              :criteria (custom-criterion "meltdowner.lv4")
              :trigger-key {:kind :level-change :category :meltdowner :level 4}} {})
   (base-ach {:id "meltdowner.lv5" :tab :meltdowner :parent "meltdowner.lv4" :icon "my_mod:electron_missile"
              :criteria (custom-criterion "meltdowner.lv5")
              :trigger-key {:kind :level-change :category :meltdowner :level 5}} {})
   (base-ach {:id "meltdowner.rad_intensify" :tab :meltdowner :parent "meltdowner.lv1" :icon "my_mod:rad_intensify"
              :criteria (custom-criterion "meltdowner.rad_intensify")
              :trigger-key {:kind :skill-learn :skill-id :rad-intensify}} {})
   (base-ach {:id "meltdowner.light_shield" :tab :meltdowner :parent "meltdowner.rad_intensify" :icon "my_mod:light_shield"
              :criteria (custom-criterion "meltdowner.light_shield")
              :trigger-key {:kind :skill-perform :skill-id :light-shield}} {})
   (base-ach {:id "meltdowner.meltdowner" :tab :meltdowner :parent "meltdowner.light_shield" :icon "my_mod:meltdowner"
              :criteria (custom-criterion "meltdowner.meltdowner")
              :trigger-key {:kind :skill-perform :skill-id :meltdowner}} {})
   (base-ach {:id "meltdowner.mine_ray" :tab :meltdowner :parent "meltdowner.meltdowner" :icon "my_mod:mine_ray"
              :criteria (custom-criterion "meltdowner.mine_ray")
              :trigger-key {:kind :skill-perform :skill-id :mine-ray-basic}} {})
   (base-ach {:id "meltdowner.jet_engine" :tab :meltdowner :parent "meltdowner.mine_ray" :icon "my_mod:jet_engine"
              :criteria (custom-criterion "meltdowner.jet_engine")
              :trigger-key {:kind :skill-perform :skill-id :jet-engine}} {})
   (base-ach {:id "meltdowner.electron_missile" :tab :meltdowner :parent "meltdowner.jet_engine" :icon "my_mod:electron_missile"
              :criteria (custom-criterion "meltdowner.electron_missile")
              :trigger-key {:kind :skill-perform :skill-id :electron-missile}} {})

   ;; Teleporter
   (base-ach {:id "teleporter.lv1" :tab :teleporter :parent nil :icon "my_mod:threatening_teleport"
              :criteria (custom-criterion "teleporter.lv1")
              :trigger-key {:kind :level-change :category :teleporter :level 1}} {})
   (base-ach {:id "teleporter.lv2" :tab :teleporter :parent "teleporter.lv1" :icon "my_mod:threatening_teleport"
              :criteria (custom-criterion "teleporter.lv2")
              :trigger-key {:kind :level-change :category :teleporter :level 2}} {})
   (base-ach {:id "teleporter.lv3" :tab :teleporter :parent "teleporter.lv2" :icon "my_mod:location_teleport"
              :criteria (custom-criterion "teleporter.lv3")
              :trigger-key {:kind :level-change :category :teleporter :level 3}} {})
   (base-ach {:id "teleporter.lv4" :tab :teleporter :parent "teleporter.lv3" :icon "my_mod:flashing"
              :criteria (custom-criterion "teleporter.lv4")
              :trigger-key {:kind :level-change :category :teleporter :level 4}} {})
   (base-ach {:id "teleporter.lv5" :tab :teleporter :parent "teleporter.lv4" :icon "my_mod:flashing"
              :criteria (custom-criterion "teleporter.lv5")
              :trigger-key {:kind :level-change :category :teleporter :level 5}} {})
   (base-ach {:id "teleporter.threatening_teleport" :tab :teleporter :parent "teleporter.lv1" :icon "my_mod:threatening_teleport"
              :criteria (custom-criterion "teleporter.threatening_teleport")
              :trigger-key {:kind :skill-perform :skill-id :threatening-teleport}} {})
   (base-ach {:id "teleporter.critical_attack" :tab :teleporter :parent "teleporter.threatening_teleport" :icon "my_mod:dim_folding_theorem"
              :criteria (custom-criterion "teleporter.critical_attack")
              :trigger-key {:kind :custom :event-id "teleporter.critical_attack"}} {})
   (base-ach {:id "teleporter.ignore_barrier" :tab :teleporter :parent "teleporter.critical_attack" :icon "my_mod:penetrate_teleport"
              :criteria (custom-criterion "teleporter.ignore_barrier")
              :trigger-key {:kind :custom :event-id "teleporter.ignore_barrier"}} {})
   (base-ach {:id "teleporter.flashing" :tab :teleporter :parent "teleporter.ignore_barrier" :icon "my_mod:flashing"
              :criteria (custom-criterion "teleporter.flashing")
              :trigger-key {:kind :skill-perform :skill-id :flashing}} {})
   (base-ach {:id "teleporter.mastery" :tab :teleporter :parent "teleporter.flashing" :icon "my_mod:space_fluct"
              :criteria (custom-criterion "teleporter.mastery")
              :trigger-key {:kind :custom :event-id "teleporter.mastery"}} {})

   ;; Vecmanip
   (base-ach {:id "vecmanip.lv1" :tab :vecmanip :parent nil :icon "my_mod:groundshock"
              :criteria (custom-criterion "vecmanip.lv1")
              :trigger-key {:kind :level-change :category :vecmanip :level 1}} {})
   (base-ach {:id "vecmanip.lv2" :tab :vecmanip :parent "vecmanip.lv1" :icon "my_mod:groundshock"
              :criteria (custom-criterion "vecmanip.lv2")
              :trigger-key {:kind :level-change :category :vecmanip :level 2}} {})
   (base-ach {:id "vecmanip.lv3" :tab :vecmanip :parent "vecmanip.lv2" :icon "my_mod:storm_wing"
              :criteria (custom-criterion "vecmanip.lv3")
              :trigger-key {:kind :level-change :category :vecmanip :level 3}} {})
   (base-ach {:id "vecmanip.lv4" :tab :vecmanip :parent "vecmanip.lv3" :icon "my_mod:blood_retrograde"
              :criteria (custom-criterion "vecmanip.lv4")
              :trigger-key {:kind :level-change :category :vecmanip :level 4}} {})
   (base-ach {:id "vecmanip.lv5" :tab :vecmanip :parent "vecmanip.lv4" :icon "my_mod:vec_reflection"
              :criteria (custom-criterion "vecmanip.lv5")
              :trigger-key {:kind :level-change :category :vecmanip :level 5}} {})
   (base-ach {:id "vecmanip.ground_shock" :tab :vecmanip :parent "vecmanip.lv1" :icon "my_mod:groundshock"
              :criteria (custom-criterion "vecmanip.ground_shock")
              :trigger-key {:kind :skill-perform :skill-id :groundshock}} {})
   (base-ach {:id "vecmanip.dir_blast" :tab :vecmanip :parent "vecmanip.ground_shock" :icon "my_mod:directed_blastwave"
              :criteria (custom-criterion "vecmanip.dir_blast")
              :trigger-key {:kind :skill-perform :skill-id :directed-blastwave}} {})
   (base-ach {:id "vecmanip.storm_wing" :tab :vecmanip :parent "vecmanip.dir_blast" :icon "my_mod:storm_wing"
              :criteria (custom-criterion "vecmanip.storm_wing")
              :trigger-key {:kind :skill-perform :skill-id :storm-wing}} {})
   (base-ach {:id "vecmanip.blood_retro" :tab :vecmanip :parent "vecmanip.storm_wing" :icon "my_mod:blood_retrograde"
              :criteria (custom-criterion "vecmanip.blood_retro")
              :trigger-key {:kind :skill-perform :skill-id :blood-retrograde}} {})
   (base-ach {:id "vecmanip.vec_reflection" :tab :vecmanip :parent "vecmanip.blood_retro" :icon "my_mod:vec_reflection"
              :criteria (custom-criterion "vecmanip.vec_reflection")
              :trigger-key {:kind :skill-perform :skill-id :vec-reflection}} {})])

