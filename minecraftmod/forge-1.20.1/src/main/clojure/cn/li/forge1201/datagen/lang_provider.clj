(ns cn.li.forge1201.datagen.lang-provider
  "Language file data generator - generates translation files from metadata"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.ac.achievement.registry :as ach-reg])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput]
           [java.nio.file Path]
           [java.util.concurrent CompletableFuture]
           [com.google.gson Gson GsonBuilder JsonElement]))

;; 1. 显式类型暗示的变量
(def ^:private ^Gson gson
  (-> (GsonBuilder.) (.setPrettyPrinting) (.disableHtmlEscaping) (.create)))

;; TODO: Load translation data from ac metadata system instead of hardcoding
;; For now, keep minimal translations for creative tab and commands
(def ^:private base-lang-data
  {"en_us.json" {"itemGroup.my_mod.items" "My Mod Items"
                 ;; Command translations
                 "command.academy.acach.success" "Granted advancement %s to %s"
                 "command.academy.acach.not_found" "Advancement not found: %s"
                 "command.academy.acach.missing_advancement" "Missing advancement argument"
                 "command.academy.aim.cat.success" "Switched to category: %s"
                 "command.academy.aim.cat.not_found" "Category not found: %s"
                 "command.academy.aim.catlist.success" "Available categories: %s"
                 "command.academy.aim.catlist.empty" "No categories available"
                 "command.academy.aim.reset.success" "Reset all abilities"
                 "command.academy.aim.learn.success" "Learned skill: %s"
                 "command.academy.aim.learn.not_found" "Skill not found: %s"
                 "command.academy.aim.learn.already" "Already learned: %s"
                 "command.academy.aim.unlearn.success" "Unlearned skill: %s"
                 "command.academy.aim.unlearn.not_found" "Skill not found: %s"
                 "command.academy.aim.learn_all.success" "Learned all skills in category"
                 "command.academy.aim.learned.list" "Learned skills: %s"
                 "command.academy.aim.skills.list" "Available skills: %s"
                 "command.academy.aim.level.success" "Set level to %s"
                 "command.academy.aim.level.invalid" "Invalid level: %s (must be 1-5)"
                 "command.academy.aim.exp.success" "Set experience for %s to %s"
                 "command.academy.aim.exp.not_found" "Skill not found: %s"
                 "command.academy.aim.exp.invalid" "Invalid experience value: %s (must be 0.0-1.0)"
                 "command.academy.aim.fullcp.success" "Restored CP to full"
                 "command.academy.aim.cd_clear.success" "Cleared all cooldowns"
                 "command.academy.aim.maxout.success" "Maxed out progression"
                 "command.academy.aim.cheats_on.success" "Enabled cheat mode"
                 "command.academy.aim.cheats_off.success" "Disabled cheat mode"
                 "command.academy.aim.help" "Available commands: cat, catlist, reset, learn, unlearn, learn_all, learned, skills, level, exp, fullcp, cd_clear, maxout, help, cheats_on, cheats_off"
                 "ability.skill.generic.brain_course" "Brain Course"
                 "ability.skill.generic.brain_course.desc" "Passive: Increases max CP by 1000."
                 "ability.skill.generic.brain_course_advanced" "Advanced Brain Course"
                 "ability.skill.generic.brain_course_advanced.desc" "Passive: Increases max CP by 1500 and max overload by 100."
                 "ability.skill.generic.mind_course" "Mind Course"
                 "ability.skill.generic.mind_course.desc" "Passive: Increases CP recovery speed by 20%."
                 "ability.skill.meltdowner.rad_intensify" "Radiation Intensify"
                 "ability.skill.meltdowner.rad_intensify.desc" "Passive: Marks targets hit by meltdowner attacks and amplifies damage for a short duration."
                 "ability.skill.teleporter.dim_folding_theorem" "Dim Folding Theorem"
                 "ability.skill.teleporter.dim_folding_theorem.desc" "Passive: Improves teleporter critical strike chance."
                 "ability.skill.teleporter.space_fluct" "Space Fluctuation"
                 "ability.skill.teleporter.space_fluct.desc" "Passive: Unlocks higher teleporter critical strike tiers."}
   "zh_cn.json" {"itemGroup.my_mod.items" "My Mod Items"
                 ;; 命令翻译
                 "command.academy.acach.success" "已授予进度 %s 给 %s"
                 "command.academy.acach.not_found" "未找到进度: %s"
                 "command.academy.acach.missing_advancement" "缺少进度参数"
                 "command.academy.aim.cat.success" "已切换到类别: %s"
                 "command.academy.aim.cat.not_found" "未找到类别: %s"
                 "command.academy.aim.catlist.success" "可用类别: %s"
                 "command.academy.aim.catlist.empty" "没有可用类别"
                 "command.academy.aim.reset.success" "已重置所有能力"
                 "command.academy.aim.learn.success" "已学习技能: %s"
                 "command.academy.aim.learn.not_found" "未找到技能: %s"
                 "command.academy.aim.learn.already" "已经学习过: %s"
                 "command.academy.aim.unlearn.success" "已遗忘技能: %s"
                 "command.academy.aim.unlearn.not_found" "未找到技能: %s"
                 "command.academy.aim.learn_all.success" "已学习类别中的所有技能"
                 "command.academy.aim.learned.list" "已学习的技能: %s"
                 "command.academy.aim.skills.list" "可用技能: %s"
                 "command.academy.aim.level.success" "已设置等级为 %s"
                 "command.academy.aim.level.invalid" "无效等级: %s (必须为 1-5)"
                 "command.academy.aim.exp.success" "已设置 %s 的经验值为 %s"
                 "command.academy.aim.exp.not_found" "未找到技能: %s"
                 "command.academy.aim.exp.invalid" "无效经验值: %s (必须为 0.0-1.0)"
                 "command.academy.aim.fullcp.success" "已恢复CP至满值"
                 "command.academy.aim.cd_clear.success" "已清除所有冷却"
                 "command.academy.aim.maxout.success" "已最大化进度"
                 "command.academy.aim.cheats_on.success" "已启用作弊模式"
                 "command.academy.aim.cheats_off.success" "已禁用作弊模式"
                 "command.academy.aim.help" "可用命令: cat, catlist, reset, learn, unlearn, learn_all, learned, skills, level, exp, fullcp, cd_clear, maxout, help, cheats_on, cheats_off"
                 "ability.skill.generic.brain_course" "脑域课程"
                 "ability.skill.generic.brain_course.desc" "被动：最大CP增加1000。"
                 "ability.skill.generic.brain_course_advanced" "高级脑域课程"
                 "ability.skill.generic.brain_course_advanced.desc" "被动：最大CP增加1500，最大过载增加100。"
                 "ability.skill.generic.mind_course" "意念课程"
                 "ability.skill.generic.mind_course.desc" "被动：CP恢复速度提升20%。"
                 "ability.skill.meltdowner.rad_intensify" "辐射强化"
                 "ability.skill.meltdowner.rad_intensify.desc" "被动：命中目标后短时间标记并提高其受到的伤害。"
                 "ability.skill.teleporter.dim_folding_theorem" "维度折叠理论"
                 "ability.skill.teleporter.dim_folding_theorem.desc" "被动：提升瞬移系暴击触发概率。"
                 "ability.skill.teleporter.space_fluct" "空间波动"
                 "ability.skill.teleporter.space_fluct.desc" "被动：解锁更高阶的瞬移暴击效果。"}})

(defn- tab-translations
  []
  {"en_us" {"advancement.my_mod.tab.default" "AcademyCraft"
            "advancement.my_mod.tab.default.description" "Core progression."
            "advancement.my_mod.tab.electromaster" "Electromaster"
            "advancement.my_mod.tab.electromaster.description" "Electromaster advancement path."
            "advancement.my_mod.tab.meltdowner" "Meltdowner"
            "advancement.my_mod.tab.meltdowner.description" "Meltdowner advancement path."
            "advancement.my_mod.tab.teleporter" "Teleporter"
            "advancement.my_mod.tab.teleporter.description" "Teleporter advancement path."
            "advancement.my_mod.tab.vecmanip" "Vector Manipulator"
            "advancement.my_mod.tab.vecmanip.description" "Vector manipulation advancement path."}
   "zh_cn" {"advancement.my_mod.tab.default" "AcademyCraft"
            "advancement.my_mod.tab.default.description" "核心进度。"
            "advancement.my_mod.tab.electromaster" "电击使"
            "advancement.my_mod.tab.electromaster.description" "电击使成就线路。"
            "advancement.my_mod.tab.meltdowner" "熔毁使"
            "advancement.my_mod.tab.meltdowner.description" "熔毁使成就线路。"
            "advancement.my_mod.tab.teleporter" "空间使"
            "advancement.my_mod.tab.teleporter.description" "空间使成就线路。"
            "advancement.my_mod.tab.vecmanip" "矢量操控"
            "advancement.my_mod.tab.vecmanip.description" "矢量操控成就线路。"}})

(defn- merged-lang-data
  []
  (let [{:keys [en_us zh_cn]} (ach-reg/translation-maps)
        tabs (tab-translations)]
    {"en_us.json" (merge (get base-lang-data "en_us.json")
                         (get tabs "en_us")
                         en_us)
     "zh_cn.json" (merge (get base-lang-data "zh_cn.json")
                         (get tabs "zh_cn")
                         zh_cn)}))

(defn create
  [^PackOutput pack-output _exfile-helper]
  (let [out-root (.getOutputFolder pack-output)
        ^Path base (.resolve ^Path out-root (str "assets/" modid/*mod-id* "/lang"))]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [results (atom [])]
          ;; 遍历数据，直接在这里调用，避免跨函数编译问题
          (doseq [[file-name data] (merged-lang-data)]
            (let [target-path (.resolve base ^String file-name)
                  ;; 手动将 Map 转为 JsonElement，确保类型匹配
                  json-tree   (.toJsonTree gson data)]
              ;; 使用完全限定名确保静态调用
              (swap! results conj (DataProvider/saveStable cached json-tree target-path))))

          (CompletableFuture/allOf (into-array CompletableFuture @results))))

      (getName [_] (str modid/*mod-id* " Lang Provider")))))