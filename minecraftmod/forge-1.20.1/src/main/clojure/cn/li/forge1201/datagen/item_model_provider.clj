(ns cn.li.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model生成器 - 直接从item定义提取

   架构：
   - core/item/*.clj: 定义items，model信息存储在:properties :model中
   - 本文件: 使用Forge/Minecraft API从item registry生成JSON（平台特定）

   优势：数据不分散，直接从item定义提取，单一信息源

   AcademyCraft-style energy tiers (e.g. developer_portable): optional
   :item-model-energy-levels in :properties — generates base + _half/_full
   sibling models and overrides on predicate <modid>:energy (see client
   `energy-item-model-properties`)."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.forge1201.datagen.resource-location :as rl]
            [clojure.string :as str])
  (:import [net.minecraft.data PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.client.model.generators ItemModelProvider ItemModelBuilder ModelFile$ExistingModelFile]))

(defn- texture-rl ^ResourceLocation
  [texture-name]
  (ResourceLocation. modid/*mod-id* (str "item/" texture-name)))

(defn- registry-model-basename
  ^String [item-id]
  (str/replace (str item-id) #"-" "_"))

(defn- register-energy-tier-models!
  [^ItemModelProvider this-provider ^ExistingFileHelper exfile-helper
   item-id {:keys [texture-empty texture-half texture-full]}]
  (let [base (registry-model-basename item-id)
        empty-t (or texture-empty (str base "_empty"))
        half-t (or texture-half (str base "_half"))
        full-t (or texture-full (str base "_full"))
        half-model (str base "_half")
        full-model (str base "_full")
        mod-s modid/*mod-id*
        energy-rl (ResourceLocation. mod-s "energy")
        gen-parent (.mcLoc this-provider "item/generated")
        half-rl (ResourceLocation. mod-s (str "item/" half-model))
        full-rl (ResourceLocation. mod-s (str "item/" full-model))]
    (doto (.withExistingParent this-provider half-model gen-parent)
      (.texture "layer0" ^ResourceLocation (texture-rl half-t)))
    (doto (.withExistingParent this-provider full-model gen-parent)
      (.texture "layer0" ^ResourceLocation (texture-rl full-t)))
    (let [^ItemModelBuilder main-b (.withExistingParent this-provider base gen-parent)
          half-mf (ModelFile$ExistingModelFile. half-rl exfile-helper)
          full-mf (ModelFile$ExistingModelFile. full-rl exfile-helper)]
      (.texture main-b "layer0" ^ResourceLocation (texture-rl empty-t))
      ;; Full threshold first so energy=1 resolves to full, not half (first-match wins).
      (-> (.override main-b)
          (.predicate energy-rl 1.0)
          (.model full-mf)
          (.end))
      (-> (.override main-b)
          (.predicate energy-rl 0.5)
          (.model half-mf)
          (.end)))))

(defn create
  "创建Item Model DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output ^ExistingFileHelper exfile-helper]
  (proxy [ItemModelProvider] [pack-output modid/*mod-id* exfile-helper]
    (registerModels []
      (let [this-provider ^ItemModelProvider this
            all-item-names (item-dsl/list-items)
            energy-tier-items (filter #(get-in (item-dsl/get-item %) [:properties :item-model-energy-levels])
                                      all-item-names)
            items-with-simple-model (keep (fn [item-name]
                                            (let [item-spec (item-dsl/get-item item-name)]
                                              (when (and (not (get-in item-spec [:properties :item-model-energy-levels]))
                                                         (get-in item-spec [:properties :model-texture]))
                                                {:item-name (str item-name)
                                                 :model-texture (get-in item-spec [:properties :model-texture])
                                                 :model-parent (get-in item-spec [:properties :model-parent] "item/generated")})))
                                          all-item-names)]
        (doseq [item-name energy-tier-items]
          (register-energy-tier-models!
            this-provider exfile-helper item-name
            (get-in (item-dsl/get-item item-name) [:properties :item-model-energy-levels])))
        (doseq [{:keys [item-name model-texture model-parent]} items-with-simple-model]
          (let [^String item-name item-name
                ^String model-texture (str model-texture)
                ^ResourceLocation parent-rl (or (rl/parse-resource-location (or model-parent "item/generated"))
                                                (.mcLoc this-provider "item/generated"))
                ^ItemModelBuilder builder (.withExistingParent this-provider item-name parent-rl)]
            (.texture builder "layer0" ^ResourceLocation (texture-rl model-texture))))
        (println (str "[item-model-provider] summary: items=" (count all-item-names)
                      ", energy-tier=" (count energy-tier-items)
                      ", simple-model=" (count items-with-simple-model)))))))
