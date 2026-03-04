(ns my-mod.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model生成器 - 直接从item定义提取
   
   架构：
   - core/item/*.clj: 定义items，model信息存储在:properties :model中
   - 本文件: 使用Forge/Minecraft API从item registry生成JSON（平台特定）
   
   优势：数据不分散，直接从item定义提取，单一信息源"
  (:require [my-mod.config.modid :as modid]
            [my-mod.item.dsl :as item-dsl]
            [my-mod.forge1201.datagen.json-util :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator CachedOutput]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute])
  (:gen-class
   :name my-mod.forge1201.datagen.ItemModelProvider
   :extends Object
   :implements [net.minecraft.data.DataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.CachedOutput] void]
             [getName [] String]]))

(defn -init [generator exfileHelper]
  [[] {:generator generator :exfileHelper exfileHelper}])

(declare generate-item-model!)

(defn -run [this ^CachedOutput cache]
  (let [{:keys [generator]} (.state this)
        all-item-names (item-dsl/list-items)]
    (doseq [item-name all-item-names]
      (let [item-spec (item-dsl/get-item item-name)]
        (when-let [model-texture (get-in item-spec [:properties :model-texture])]
          (let [model-parent (get-in item-spec [:properties :model-parent] "item/generated")]
            (generate-item-model! generator item-name model-texture model-parent)))))))

(defn -getName [this]
  "MyMod Item Models (from item registry)")

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
  [^DataGenerator generator item-name texture-name model-parent]
  (let [model-data (texture-name->model-json texture-name model-parent)
        output-folder (.getOutputFolder generator)
        models-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "models") (resolve "item"))
        file-path (.resolve models-dir (str item-name ".json"))]
    
    (try
      (Files/createDirectories models-dir (make-array FileAttribute 0))
      (spit (.toFile file-path) (json/write-json model-data))
      (println (str "[" modid/MOD-ID "] Generated item model: " item-name ".json"))
      (catch Exception e
        (println (str "[" modid/MOD-ID "] ERROR generating item model " item-name ": " (.getMessage e)))))))))
