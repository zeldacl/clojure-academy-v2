(ns cn.li.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model生成器 - 直接从item定义提取

   架构：
   - core/item/*.clj: 定义items，model信息存储在:properties :model中
   - 本文件: 使用Forge/Minecraft API从item registry生成JSON（平台特定）

   优势：数据不分散，直接从item定义提取，单一信息源"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.forge1201.datagen.resource-location :as rl])
  (:import [net.minecraft.data PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.client.model.generators ItemModelProvider]))

(defn- texture-rl
  [texture-name]
  (ResourceLocation. modid/*mod-id* (str "item/" texture-name)))

(defn create
  "创建Item Model DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output _exfile-helper]
  (proxy [ItemModelProvider] [pack-output modid/*mod-id* ^ExistingFileHelper _exfile-helper]
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
          (-> (.withExistingParent this item-name
                                   (or (rl/parse-resource-location (or model-parent "item/generated"))
                                       (.mcLoc this "item/generated")))
              (.texture "layer0" (texture-rl model-texture))))
        (println (str "[item-model-provider] summary: items=" (count all-item-names)
                      ", with-model=" (count items-with-model)
                      ", written=" (count items-with-model)))))))
