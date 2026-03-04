(ns my-mod.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model生成器 - 直接从item定义提取
   
   架构：
   - core/item/*.clj: 定义items，model信息存储在:properties :model中
   - 本文件: 使用Forge/Minecraft API从item registry生成JSON（平台特定）
   
   优势：数据不分散，直接从item定义提取，单一信息源"
  (:require [my-mod.config.modid :as modid]
            [my-mod.item.dsl :as item-dsl]
            [my-mod.forge1201.datagen.json-util :as json])
  (:import [net.minecraft.data DataGenerator DataProvider DataProvider$Factory CachedOutput PackOutput PackOutput$Target]
           [com.google.common.hash Hashing]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CompletableFuture]))

(declare generate-item-model!)

(defn create
  "创建Item Model DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output _exfile-helper]
  (reify DataProvider
    (run [_this cache]
      (let [all-item-names (item-dsl/list-items)
            items-with-model (keep (fn [item-name]
                                     (let [item-spec      (item-dsl/get-item item-name)
                                           model-texture (get-in item-spec [:properties :model-texture])]
                                       (when model-texture
                                         {:item-name item-name
                                          :model-texture model-texture
                                          :model-parent (get-in item-spec [:properties :model-parent] "item/generated")})))
                                   all-item-names)
            written-count (reduce (fn [acc {:keys [item-name model-texture model-parent]}]
                                    (if (generate-item-model! cache pack-output item-name model-texture model-parent)
                                      (inc acc)
                                      acc))
                                  0
                                  items-with-model)]
        (println (str "[item-model-provider] run called, items: " (count all-item-names)
                      ", with-model: " (count items-with-model)
                      ", written=" written-count))
        (CompletableFuture/completedFuture nil)))
    (getName [_this]
      "MyMod Item Models (from item registry)")))

;; ============================================================================
;; Item Model JSON生成（核心逻辑 - 从定义推导）
;; ============================================================================

(defn- texture-name->path
  "将 texture 名字转换为完整的资源路径
   
   参数：
     texture-name: \"wafer\"
   
   返回：
     \"modid:items/wafer\""
  [texture-name]
  (str modid/MOD-ID ":items/" texture-name))

(defn- texture-name->model-json
  "将 texture 名字转换为 item model JSON
   
   参数：
     texture-name: \"wafer\"
     parent: \"item/generated\"
   
   返回：
     {:parent \"item/generated\" :textures {:layer0 \"modid:items/wafer\"}}"
  [texture-name parent]
  {:parent parent
   :textures {:layer0 (texture-name->path texture-name)}})

;; ============================================================================
;; 文件生成
;; ============================================================================

(defn generate-item-model!
  "生成单个item的model JSON
   
   参数：
     generator: DataGenerator
     item-name: item的registry name
     texture-name: texture的名字（不含路径前缀）
     model-parent: parent model
   
   副作用：
     向输出目录写入item model JSON文件"
  [^CachedOutput cache ^PackOutput pack-output item-name texture-name model-parent]
  (try
    (let [model-data (texture-name->model-json texture-name model-parent)
          json-str   (json/write-json model-data)
          bytes      (.getBytes ^String json-str StandardCharsets/UTF_8)
          hash       (.hashBytes (Hashing/sha1) bytes)
          file-path  (.. (.getOutputFolder pack-output PackOutput$Target/RESOURCE_PACK)
                         (resolve modid/MOD-ID)
                         (resolve "models")
                         (resolve "item")
                         (resolve (str item-name ".json")))]
      (println (str "[" modid/MOD-ID "] Writing item model: " file-path))
      (.writeIfNeeded cache file-path bytes hash)
      true)
    (catch Exception e
      (println (str "[" modid/MOD-ID "] ERROR generating item model " item-name ": " (.getMessage e)))
      false)))
