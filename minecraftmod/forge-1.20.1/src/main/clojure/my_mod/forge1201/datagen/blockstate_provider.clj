(ns my-mod.forge1201.datagen.blockstate-provider
  "Forge 1.20.1 BlockState生成器 - 使用core中的定义
   
   架构：
   - core/blockstate_definition.clj: 定义block的blockstate结构（平台无关）
   - 本文件: 使用Forge/Minecraft API和定义生成JSON（平台特定）
   
   优势：定义层复用，易于支持新的Forge版本"
  (:require [my-mod.config.modid :as modid]
            [my-mod.block.blockstate-definition :as blockstate-def]
            [my-mod.forge1201.datagen.json-util :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator IDataProvider]
           [net.minecraft.data CachedOutput]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute])
  (:gen-class
   :name my-mod.forge1201.datagen.BlockStateProvider
   :extends Object
   :implements [net.minecraft.data.DataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.CachedOutput] void]
             [getName [] String]]))

;; ============================================================================
;; BlockState JSON生成（核心逻辑 - 调用core定义）
;; ============================================================================

(defn- blockstate-definition->json
  "将BlockStateDefinition转换为blockstate JSON结构
   
   参数：
     definition: BlockStateDefinition from core
   
   返回：
     标准blockstate JSON map (variants或multipart格式)"
  [definition]
  (let [parts (:parts definition)]
    (if (blockstate-def/is-multipart-block? definition)
      ;; Multipart格式
      {:multipart (vec (map (fn [part]
                              (let [base {:apply {:model (first (:models part))}}]
                                (if (:condition part)
                                  ;; 有条件
                                  (assoc base :when (:condition part))
                                  ;; 无条件
                                  base)))
                            parts))}
      ;; 单一variant格式（简单blocks）
      {:variants {"" {:model (first (:models (first parts)))}}})))

;; ============================================================================
;; 生成器实现 - 使用高级API的骨架
;; ============================================================================

(defn -init [generator exfileHelper]
  [[] {:generator generator :exfileHelper exfileHelper}])

(defn -run [this ^CachedOutput cache]
  (let [{:keys [generator]} (.state this)
        all-defs (blockstate-def/get-all-definitions)]
    (doseq [[block-key definition] all-defs]
      (generate-blockstate! generator definition))))

(defn -getName [this]
  "MyMod BlockStates (from core definitions)")

;; ============================================================================
;; 文件生成
;; ============================================================================

(defn generate-blockstate!
  "生成单个block的blockstate JSON
   
   参数：
     generator: DataGenerator
     definition: BlockStateDefinition
   
   副作用：
     向输出目录写入blockstate JSON文件"
  [^DataGenerator generator definition]
  (let [registry-name (:registry-name definition)
        blockstate-data (blockstate-definition->json definition)
        output-folder (.getOutputFolder generator)
        blockstates-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "blockstates"))
        file-path (.resolve blockstates-dir (str registry-name ".json"))]
    
    (try
      (Files/createDirectories blockstates-dir (make-array FileAttribute 0))
      (spit (.toFile file-path) (json/write-json blockstate-data))
      (println (str "[" modid/MOD-ID "] Generated blockstate: " registry-name ".json"))
    (catch Exception e
      (println (str "[" modid/MOD-ID "] Error generating blockstate " registry-name ": " e))
      (.printStackTrace e)))))

;; ============================================================================
;; 关键点
;; ============================================================================

(comment
  ;; 这个实现的优势：
  ;; 
  ;; 1. 定义与实现分离
  ;;    - core/*.clj: BlockState定义（独立平台）
  ;;    - forge-1.20.1/*.clj: 生成实现（Forge特定）
  ;;
  ;; 2. 易于扩展
  ;;    - 添加fabric-1.20.1时，可复用blockstate_definition
  ;;    - 只需创建fabric版的datagen实现
  ;;
  ;; 3. 类型安全
  ;;    - BlockStateDefinition是明确的数据结构
  ;;    - 避免了手动拼接JSON的错误
  ;;
  ;; 4. 可维护性
  ;;    - 如果要修改blockstate结构，只需改core定义
  ;;    - 所有平台自动获得修改
  )

