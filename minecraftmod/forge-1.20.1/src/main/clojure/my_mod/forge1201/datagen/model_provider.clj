(ns my-mod.forge1201.datagen.model-provider
  "Forge 1.20.1 Model生成器 - 使用core中的定义
   
   架构：
   - core/blockstate_definition.clj: 定义block的属性和model信息（平台无关）
   - 本文件: 根据定义生成model JSON文件（平台特定）
   
   优势：定义层复用，易于支持新的Forge版本"
  (:require [my-mod.config.modid :as modid]
            [my-mod.block.blockstate-definition :as blockstate-def]
            [my-mod.registry.metadata :as registry-metadata]
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

(defn- model-id->model-name
  [model-id]
  (clojure.string/replace model-id #".*:block/" ""))

(defn- infer-registry-name-from-model
  "将模型名映射回对应的block registry name。
   例如：
   - node_basic_energy_3 -> node_basic
   - matrix -> matrix"
  [model-name]
  (if-let [[_ node-registry] (re-matches #"(node_(?:basic|standard|advanced))_(?:base|energy_\d+|connected)" model-name)]
    node-registry
    model-name))

(defn- registry-name->block-spec
  "根据registry name反查DSL block spec。"
  [registry-name]
  (some (fn [block-id]
          (when (= registry-name (registry-metadata/get-block-registry-name block-id))
            (registry-metadata/get-block-spec block-id)))
        (registry-metadata/get-all-block-ids)))

(defn- texture-from-spec
  "从DSL spec推导模型纹理：
   优先级：
   1) :model-textures {model-name texture} (官方字段)
   2) :textures {:all texture} (官方字段)
   3) :properties :model-textures {model-name texture} (兼容)
   4) :properties :textures {:all texture} (兼容)
   5) :properties :texture texture (兼容)
   4) 默认 my_mod:blocks/<model-name>"
  [block-spec model-name]
  (or (get-in block-spec [:model-textures model-name])
    (get-in block-spec [:model-textures (keyword model-name)])
    (get-in block-spec [:textures :all])
    (get-in block-spec [:properties :model-textures model-name])
    (get-in block-spec [:properties :model-textures (keyword model-name)])
      (get-in block-spec [:properties :textures :all])
      (get-in block-spec [:properties :texture])
      (str modid/MOD-ID ":blocks/" model-name)))

(defn- parent-from-spec
  "从DSL spec推导model parent，默认使用minecraft:block/cube_all。
   优先使用官方字段 :model-parent，兼容旧字段 :properties :model-parent。"
  [block-spec]
  (or (:model-parent block-spec)
    (get-in block-spec [:properties :model-parent])
      "minecraft:block/cube_all"))

(defn- extract-unique-models
  "从blockstate定义中提取所有唯一的model名称"
  [all-definitions]
  (set (mapcat (fn [[_ definition]]
                 (mapcat :models (:parts definition)))
               all-definitions)))

(defn- model-id->json
  "根据model-id创建model JSON结构（优先读取DSL/spec，回退自动推导）。"
  [model-id]
  (let [model-name (model-id->model-name model-id)
        registry-name (infer-registry-name-from-model model-name)
        block-spec (registry-name->block-spec registry-name)
        texture-all (texture-from-spec block-spec model-name)
        parent (parent-from-spec block-spec)]
    {:parent parent
     :textures {:all texture-all}}))

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
          model-name (model-id->model-name model-id)
          model-data (model-id->json model-id)
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
