(ns cn.li.ac.ability.datagen.registry
  "Datagen registry for ability domain: achievements and recipes.
   Registers metadata during datagen phase for inclusion in JSON outputs."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.datagen.teleporter-translations :as teleporter-translations]
            [cn.li.ac.achievement.registry :as achievement-registry]
            [cn.li.ac.achievement.data :as achievement-data]
            [cn.li.ac.ability.registry.category :as category-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.recipe.crafting-recipes :as crafting-recipes]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(defn- title-case-id
  [v]
  (let [text (-> (name v)
        (str/replace #"[-_]" " "))]
    (->> (str/split text #"\s+")
      (remove str/blank?)
      (map str/capitalize)
      (str/join " "))))

(defn- explicit-skill-translations
  [skills locale]
  (into {}
        (mapcat (fn [{:keys [translations]}]
                  (seq (get translations locale {}))))
        skills))

(defn- ability-translation-map
  []
  (let [categories (category-registry/get-all-categories)
      skills (skill-query/list-skills)
      teleporter-translation-map (teleporter-translations/translation-map)
        category-name-entries
        (into {}
              (keep (fn [{:keys [id name-key]}]
                      (when (and (keyword? id) (string? name-key))
                        [name-key (title-case-id id)])))
              categories)
        skill-name-entries
        (into {}
              (keep (fn [{:keys [id name-key]}]
                      (when (and (keyword? id) (string? name-key))
                        [name-key (title-case-id id)])))
              skills)
        skill-desc-entries
        (into {}
              (keep (fn [{:keys [id description-key]}]
                      (when (and (keyword? id) (string? description-key))
                        [description-key (str "Ability skill: " (title-case-id id))])))
              skills)
        explicit-en (explicit-skill-translations skills :en_us)
        explicit-zh (explicit-skill-translations skills :zh_cn)
        en (merge category-name-entries
                  skill-name-entries
                  skill-desc-entries
                  explicit-en
                  (:en_us teleporter-translation-map))
        zh (merge en
                  explicit-zh
                  (:zh_cn teleporter-translation-map))]
      {:en_us en
       :zh_cn zh}))

(def ^:private command-translation-map
  {:en_us
   {"command.academy.acach.missing_advancement" "Missing advancement argument"
    "command.academy.aim.cat.success" "Switched to category: %s"
    "command.academy.aim.cat.not_found" "Category not found: %s"
    "command.academy.aim.catlist.success" "Available categories: %s"
    "command.academy.aim.catlist.empty" "No categories available"
    "command.academy.aim.reset.success" "Reset all abilities"
    "command.academy.aim.node.learn.success" "Learned node: %s"
    "command.academy.aim.node.learn.not_found" "Node not found: %s"
    "command.academy.aim.node.learn.already" "Already learned: %s"
    "command.academy.aim.node.unlearn.success" "Unlearned node: %s"
    "command.academy.aim.node.unlearn.not_found" "Node not found: %s"
    "command.academy.aim.node.learn_all.success" "Learned all nodes in category"
    "command.academy.aim.node.learned.list" "Learned nodes: %s"
    "command.academy.aim.node.list" "Available nodes: %s"
    "command.academy.aim.level.success" "Set level to %s"
    "command.academy.aim.level.invalid" "Invalid level: %s (must be 1-5)"
    "command.academy.aim.node.exp.success" "Set experience for %s to %s"
    "command.academy.aim.node.exp.not_found" "Node not found: %s"
    "command.academy.aim.node.exp.invalid" "Invalid experience value: %s (must be 0.0-1.0)"
    "command.academy.aim.fullcp.success" "Restored CP to full"
    "command.academy.aim.cd_clear.success" "Cleared all cooldowns"
    "command.academy.aim.maxout.success" "Maxed out progression"
    "command.academy.aim.cheats_on.success" "Enabled cheat mode"
    "command.academy.aim.cheats_off.success" "Disabled cheat mode"
    "command.academy.aim.help" "Available commands: cat, catlist, reset, learn, unlearn, learn_all, learned, nodes, level, exp, fullcp, cd_clear, maxout, help, cheats_on, cheats_off"}
   :zh_cn
   {"command.academy.acach.missing_advancement" "缺少进度参数"
    "command.academy.aim.cat.success" "已切换到类别: %s"
    "command.academy.aim.cat.not_found" "未找到类别: %s"
    "command.academy.aim.catlist.success" "可用类别: %s"
    "command.academy.aim.catlist.empty" "没有可用类别"
    "command.academy.aim.reset.success" "已重置所有能力"
    "command.academy.aim.node.learn.success" "已学习节点: %s"
    "command.academy.aim.node.learn.not_found" "未找到节点: %s"
    "command.academy.aim.node.learn.already" "已经学习过: %s"
    "command.academy.aim.node.unlearn.success" "已遗忘节点: %s"
    "command.academy.aim.node.unlearn.not_found" "未找到节点: %s"
    "command.academy.aim.node.learn_all.success" "已学习类别中的所有节点"
    "command.academy.aim.node.learned.list" "已学习的节点: %s"
    "command.academy.aim.node.list" "可用节点: %s"
    "command.academy.aim.level.success" "已设置等级为 %s"
    "command.academy.aim.level.invalid" "无效等级: %s (必须为 1-5)"
    "command.academy.aim.node.exp.success" "已设置 %s 的经验值为 %s"
    "command.academy.aim.node.exp.not_found" "未找到节点: %s"
    "command.academy.aim.node.exp.invalid" "无效经验值: %s (必须为 0.0-1.0)"
    "command.academy.aim.fullcp.success" "已恢复CP至满值"
    "command.academy.aim.cd_clear.success" "已清除所有冷却"
    "command.academy.aim.maxout.success" "已最大化进度"
    "command.academy.aim.cheats_on.success" "已启用作弊模式"
    "command.academy.aim.cheats_off.success" "已禁用作弊模式"
    "command.academy.aim.help" "可用命令: cat, catlist, reset, learn, unlearn, learn_all, learned, nodes, level, exp, fullcp, cd_clear, maxout, help, cheats_on, cheats_off"}})

(defn register-datagen-metadata!
  "Register ability domain's datagen content into shared metadata registry.
   Called during datagen initialization phase."
  []
  (let [achievement-tabs (achievement-registry/all-tabs)
        achievements achievement-data/achievements
        recipes (crafting-recipes/get-all-recipes)
        achievement-translation-map (achievement-registry/translation-maps)
        ability-translation-map* (ability-translation-map)]
    (metadata/set-achievement-tabs! achievement-tabs)
    (metadata/set-achievements! achievements)
    (metadata/merge-translations! achievement-translation-map)
    (metadata/merge-translations! ability-translation-map*)
    (metadata/merge-translations! command-translation-map)
    (metadata/set-recipes! recipes)))
