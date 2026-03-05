(ns my-mod.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model生成器 - 直接从item定义提取
   
   架构：
   - core/item/*.clj: 定义items，model信息存储在:properties :model中
   - 本文件: 使用Forge/Minecraft API从item registry生成JSON（平台特定）
   
   优势：数据不分散，直接从item定义提取，单一信息源"
  (:require [my-mod.config.modid :as modid]
            [my-mod.item.dsl :as item-dsl]
            [clojure.string :as str])
  (:import [net.minecraft.data DataProvider PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.client.model.generators ItemModelProvider]))

(defn- parse-parent-rl
  [^ItemModelProvider provider parent]
  (let [value (or parent "item/generated")]
    (if (str/includes? value ":")
      (let [[namespace path] (str/split value #":" 2)]
        (ResourceLocation. namespace path))
      (.mcLoc provider value))))

(defn- texture-rl
  [texture-name]
  (ResourceLocation. modid/MOD-ID (str "items/" texture-name)))

(defn create
  "创建Item Model DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output _exfile-helper]
  (proxy [ItemModelProvider] [pack-output modid/MOD-ID ^ExistingFileHelper _exfile-helper]
    (registerModels []
      (let [all-item-names (item-dsl/list-items)
            items-with-model (keep (fn [item-name]
                                     (let [item-spec (item-dsl/get-item item-name)
                                           model-texture (get-in item-spec [:properties :model-texture])]
                                       (when model-texture
                                         {:item-name item-name
                                          :model-texture model-texture
                                          :model-parent (get-in item-spec [:properties :model-parent] "item/generated")})))
                                   all-item-names)]
        (doseq [{:keys [item-name model-texture model-parent]} items-with-model]
          (-> (.withExistingParent this item-name (parse-parent-rl this model-parent))
              (.texture "layer0" (texture-rl model-texture))))
        (println (str "[item-model-provider] summary: items=" (count all-item-names)
                      ", with-model=" (count items-with-model)
                      ", written=" (count items-with-model)))))))
