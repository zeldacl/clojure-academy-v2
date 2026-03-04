(ns my-mod.forge1201.datagen.model-provider
  "Forge 1.20.1 Model生成器 - 使用core中的定义
   
   架构：
   - core/blockstate_definition.clj: 定义block的属性和model信息（平台无关）
   - 本文件: 根据定义生成model JSON文件（平台特定）
   
   优势：定义层复用，易于支持新的Forge版本"
  (:require [my-mod.config.modid :as modid]
            [my-mod.block.blockstate-definition :as blockstate-def]
            [my-mod.forge1201.datagen.json-util :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator CachedOutput DataProvider]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute])
  (:gen-class
   :name my-mod.forge1201.datagen.ModelProvider
   :extends Object
   :implements [net.minecraft.data.DataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.CachedOutput] void]
             [getName [] String]]))

;; ============================================================================
;; Model对象创建（从core定义提取）
;; ============================================================================

(defn- extract-unique-models
  "从blockstate定义中提取所有唯一的model名称"
  [all-definitions]
  (set (mapcat (fn [[_ definition]]
                 (mapcat :models (:parts definition)))
               all-definitions)))

(defn- model-name->json
  "根据model名称创建model JSON结构
   
   简化版：所有model都使用标准的cube_all父类和简单纹理映射
   在实际项目中，可能需要更复杂的处理（如cube vs cube_all等）"
  [model-name]
  (let [;; 从model名称解析：my_mod:block/node_basic_energy_0 -> node_basic_energy_0
        texture-name (-> model-name
                        (clojure.string/replace #".*:block/" ""))]
    {:parent "minecraft:block/cube_all"
     :textures {:all (str modid/MOD-ID ":blocks/" texture-name)}}))

;; ============================================================================
;; 生成器实现
;; ============================================================================

(defn -init [generator exfileHelper]
  [[] {:generator generator :exfileHelper exfileHelper}])

(defn -run [this ^CachedOutput cache]
  (let [{:keys [generator]} (.state this)
        all-defs (blockstate-def/get-all-definitions)
        models (extract-unique-models all-defs)]
    (doseq [model-id models]
      (generate-model! generator model-id))))

(defn -getName [this]
  "MyMod Block Models (from core definitions)")

;; ============================================================================
;; 文件生成
;; ============================================================================

(defn generate-model!
  "生成单个model JSON
   
   参数：
     generator: DataGenerator
     model-id: model identifier, e.g. \"my_mod:block/node_basic_energy_0\"
   
   副作用：
     向输出目录写入model JSON文件"
  [^DataGenerator generator model-id]
  (try
    (let [;; 从model-id解析文件名：去掉 my_mod:block/ 前缀
          model-name (-> model-id
                        (clojure.string/replace #".*:block/" ""))
          model-data (model-name->json model-id)
          output-folder (.getOutputFolder generator)
          models-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "models") (resolve "block"))
          file-path (.resolve models-dir (str model-name ".json"))]
      
      (Files/createDirectories models-dir (make-array FileAttribute 0))
      (spit (.toFile file-path) (json/write-json model-data))
      (println (str "[" modid/MOD-ID "] Generated model: " model-name ".json")))
    
    (catch Exception e
      (println (str "[" modid/MOD-ID "] Error generating model " model-id ": " e))
      (.printStackTrace e))))

;; ============================================================================
;; 关键设计
;; ============================================================================

(comment
  ;; 这个实现与blockstate_provider的对应关系：
  ;;
  ;; 1. 数据流
  ;;    blockstate_definition → blockstate_provider: 生成blockstate JSON
  ;;           ↓
  ;;    blockstate_definition → model_provider: 生成model JSON
  ;;
  ;; 2. 协调
  ;;    - blockstate_provider决定哪些model在什么条件下应用
  ;;    - model_provider确保所有引用的model都存在
  ;;
  ;; 3. 扩展点
  ;;    - 修改model-name->json可以实现复杂的model逻辑（如材质变体等）
  ;;    - 修改extract-unique-models可以过滤或优化model列表
  )
