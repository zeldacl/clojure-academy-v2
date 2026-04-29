(ns cn.li.forge1201.datagen.lang-provider
  "Language file data generator - generates translation files from metadata"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
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

;; Base translations for creative tab and commands; skill/item names come from datagen-metadata.
(defn- merged-lang-data
  []
  (let [{:keys [en_us zh_cn]} (datagen-metadata/get-translation-maps)]
    {"en_us.json" (merge (get base-lang-data "en_us.json")
                          en_us)
     "zh_cn.json" (merge (get base-lang-data "zh_cn.json")
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