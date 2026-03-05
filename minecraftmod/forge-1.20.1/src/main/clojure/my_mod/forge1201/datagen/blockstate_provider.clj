(ns my-mod.forge1201.datagen.blockstate-provider
  "Forge 1.20.1 BlockState生成器 - 使用core中的定义
   
   架构：
   - core/blockstate_definition.clj: 定义block的blockstate结构（平台无关）
   - 本文件: 使用Forge/Minecraft API和定义生成JSON（平台特定）
   
   优势：定义层复用，易于支持新的Forge版本"
  (:require [my-mod.config.modid :as modid]
            [my-mod.block.blockstate-definition :as blockstate-def]
            [my-mod.forge1201.mod :as forge-mod]
            [clojure.string :as str]
            [my-mod.forge1201.datagen.json-util :as json])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput PackOutput$Target]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.level.block Blocks]
           [net.minecraftforge.client.model.generators BlockStateProvider ModelFile$UncheckedModelFile]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.registries ForgeRegistries]
           [com.google.common.hash Hashing]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CompletableFuture]))

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
      {:variants {"normal" {:model (first (:models (first parts)))}}})))

;; ============================================================================
;; 生成器实现
;; ============================================================================

(declare generate-blockstate!)

(defn- normalize-candidates
  [s]
  (when (and s (not (str/blank? s)))
    [s
     (str/replace s "-" "_")
     (str/replace s "_" "-")]))

(defn- registry-object->block
  [registry-object]
  (try
    (when (and registry-object (.isPresent registry-object))
      (.get registry-object))
    (catch Throwable _
      nil)))

(defn- resolve-from-registered-map
  [block-key registry-name]
  (let [key-name (name block-key)
        candidates (distinct (concat (normalize-candidates key-name)
                                     (normalize-candidates registry-name)))]
    (some (fn [candidate]
            (when-let [registry-object (get @forge-mod/registered-blocks candidate)]
              (registry-object->block registry-object)))
          candidates)))

(defn- resolve-from-forge-registry
  [block-key registry-name]
  (let [key-name (name block-key)
        candidates (distinct (concat (normalize-candidates registry-name)
                                     (normalize-candidates key-name)))]
    (some (fn [candidate]
            (.getValue ForgeRegistries/BLOCKS
                       (ResourceLocation. modid/MOD-ID candidate)))
          candidates)))

(defn- resolve-registered-block
  "Resolve Forge Block for datagen builder.
   Prefer DSL-key based lookup (same key used during registration),
   fallback to registry-name lookup for compatibility."
  [block-key registry-name]
  (or (resolve-from-registered-map block-key registry-name)
      (resolve-from-forge-registry block-key registry-name)))

(defn- generate-simple-blockstates-with-forge!
  "Use Forge BlockStateProvider builder for simple (single-variant) blocks."
  [^CachedOutput cache ^PackOutput pack-output ^ExistingFileHelper exfile-helper simple-defs]
  (let [registered-count (atom 0)
        fallback-defs (atom [])
        provider (proxy [BlockStateProvider] [pack-output modid/MOD-ID exfile-helper]
                   (registerStatesAndModels []
                     (doseq [[block-key definition] simple-defs
                             :let [registry-name (:registry-name definition)
                                   model-id (first (:models (first (:parts definition))))
                                   block (resolve-registered-block block-key registry-name)]]
                       (if (and block (not= block Blocks/AIR))
                         (do
                           (.simpleBlock this block (ModelFile$UncheckedModelFile. ^String model-id))
                           (swap! registered-count inc))
                         (do
                           (swap! fallback-defs conj definition)
                           (println (str "[" modid/MOD-ID "] Fallback simple blockstate JSON: block-key=" (name block-key)
                                         ", registry=" registry-name
                                         ", rl=" modid/MOD-ID ":" registry-name)))))))]
    {:future (.run provider cache)
     :registered @registered-count
     :fallback-defs @fallback-defs}))

(defn create
  "创建BlockState DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output exfile-helper]
  (reify DataProvider
    (run [_this cache]
      (let [all-defs (blockstate-def/get-all-definitions)
            simple-defs (into {} (remove (fn [[_block-key definition]]
                                           (blockstate-def/is-multipart-block? definition))
                                         all-defs))
            multipart-defs (into {} (filter (fn [[_block-key definition]]
                                              (blockstate-def/is-multipart-block? definition))
                                            all-defs))
            {:keys [future registered fallback-defs]} (generate-simple-blockstates-with-forge!
                                                       cache pack-output exfile-helper simple-defs)
            fallback-written (reduce (fn [acc definition]
                                       (if (generate-blockstate! cache pack-output definition)
                                         (inc acc)
                                         acc))
                                     0
                                     fallback-defs)
            multipart-written (reduce (fn [acc [_block-key definition]]
                                        (if (generate-blockstate! cache pack-output definition)
                                          (inc acc)
                                          acc))
                                      0
                                      multipart-defs)
            written-count (+ registered fallback-written multipart-written)]
        (println (str "[blockstate-provider] summary: defs=" (count all-defs)
                      ", simple=" (count simple-defs)
                      ", simple-fallback=" (count fallback-defs)
                      ", multipart=" (count multipart-defs)
                      ", written=" written-count))
        (CompletableFuture/allOf (into-array CompletableFuture [future]))))
    (getName [_this]
      "MyMod BlockStates (from core definitions)")))

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
  [^CachedOutput cache ^PackOutput pack-output definition]
  (try
    (let [registry-name (:registry-name definition)
          blockstate-data (blockstate-definition->json definition)
          json-str   (json/write-json blockstate-data)
          bytes      (.getBytes ^String json-str StandardCharsets/UTF_8)
          hash       (.hashBytes (Hashing/sha1) bytes)
          output-folder (.getOutputFolder pack-output PackOutput$Target/RESOURCE_PACK)
          file-path  (.. output-folder
                         (resolve modid/MOD-ID)
                         (resolve "blockstates")
                         (resolve (str registry-name ".json")))]
      (println (str "[" modid/MOD-ID "] Writing blockstate: " file-path))
      (.writeIfNeeded cache file-path bytes hash)
      true)
    (catch Exception e
      (println (str "[" modid/MOD-ID "] Error generating blockstate: " e))
      (.printStackTrace e)
      false)))

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

