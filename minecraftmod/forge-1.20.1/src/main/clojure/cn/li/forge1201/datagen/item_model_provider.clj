(ns cn.li.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model生成器 - 直接从item定义提取

   架构：
   - core/item/*.clj: 定义items，model信息存储在:properties :model中
   - 本文件: 使用Forge/Minecraft API从item registry生成JSON（平台特定）

   优势：数据不分散，直接从item定义提取，单一信息源

  Optional :item-model-energy-levels in :properties generates base + tiered
  sibling models and overrides on predicate <modid>:energy (see client
  `energy-item-model-properties`)."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.item-model-provider-core :as item-model-core])
  (:import [net.minecraft.data PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.client.model.generators ItemModelProvider ItemModelBuilder ModelFile$ExistingModelFile]))

(defn- apply-model-spec!
  [^ItemModelProvider provider ^ExistingFileHelper exfile-helper {:keys [model-name json]}]
  (let [^ResourceLocation parent-rl (or (rl/parse-resource-location (:parent json))
                                        (.mcLoc provider "item/generated"))
        ^ItemModelBuilder builder (.withExistingParent provider (str model-name) parent-rl)]
    (doseq [[layer texture-id] (:textures json)]
      (.texture builder (name layer) ^ResourceLocation (rl/parse-resource-location texture-id modid/*mod-id*)))
    (doseq [{:keys [predicate model]} (:overrides json)]
      (let [override-builder (.override builder)
            model-file (ModelFile$ExistingModelFile. (rl/parse-resource-location model modid/*mod-id*) exfile-helper)]
        (doseq [[predicate-id value] predicate]
          (.predicate override-builder (rl/parse-resource-location predicate-id modid/*mod-id*) (float value)))
        (.model override-builder model-file)
        (.end override-builder)))
    builder))

(defn create
  "创建Item Model DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output ^ExistingFileHelper exfile-helper]
  (proxy [ItemModelProvider] [pack-output modid/*mod-id* exfile-helper]
    (registerModels []
      (let [this-provider ^ItemModelProvider this
            {:keys [all-item-count energy-tier-count simple-count models]} (item-model-core/gather-model-specs)]
        (doseq [model-spec models]
          (apply-model-spec! this-provider exfile-helper model-spec))
        (println (str "[item-model-provider] summary: items=" all-item-count
                      ", energy-tier=" energy-tier-count
                      ", simple-model=" simple-count))))))
