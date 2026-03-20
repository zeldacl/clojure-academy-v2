(ns cn.li.forge1201.datagen.blockstate-provider
  "Forge 1.20.1 BlockState生成器 - 使用core中的定义
   
   架构：
   - core/blockstate_definition.clj: 定义block的blockstate结构（平台无关）
   - 本文件: 使用Forge/Minecraft API和定义生成JSON（平台特定）
   
   优势：定义层复用，易于支持新的Forge版本"
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.blockstate-definition :as blockstate-def]
            [cn.li.forge1201.blockstate-properties :as bsp]
            [cn.li.forge1201.mod :as forge-mod]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.level.block Blocks]
           [net.minecraftforge.client.model.generators BlockStateProvider]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.registries ForgeRegistries]
           [cn.li.forge1201.block DynamicStateBlock]
           [java.util.concurrent CompletableFuture]))


(defn- parse-rl
  ([s] (parse-rl s modid/MOD-ID))
  ([s default-namespace]
   (let [value (str s)]
     (if (str/includes? value ":")
       (let [[namespace path] (str/split value #":" 2)]
         (ResourceLocation. namespace path))
       (ResourceLocation. default-namespace value)))))

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

(def ^:private forge-blocks-registry
  ;; Delay static field access to avoid triggering Minecraft registry bootstrap
  ;; during AOT/checkClojure class loading.
  (delay ForgeRegistries/BLOCKS))

(defn- resolve-from-forge-registry
  [block-key registry-name]
  (let [key-name (name block-key)
        candidates (distinct (concat (normalize-candidates registry-name)
                                     (normalize-candidates key-name)))]
    (some (fn [candidate]
            (.getValue (force forge-blocks-registry)
                       (ResourceLocation. modid/MOD-ID candidate)))
          candidates)))

(defn- resolve-registered-block
  "Resolve block object for datagen builder."
  [block-key registry-name]
  (or (resolve-from-registered-map block-key registry-name)
      (resolve-from-forge-registry block-key registry-name)))

(defn- infer-registry-name-from-model
  "Infer block registry name from model name.
   For default blocks, model name = registry name."
  [model-name]
  model-name)

(defn- registry-name->block-spec
  [registry-name]
  (some (fn [block-id]
          (when (= registry-name (registry-metadata/get-block-registry-name block-id))
            (registry-metadata/get-block-spec block-id)))
        (registry-metadata/get-all-block-ids)))

(defn- normalize-block-texture
  "Keep texture paths as-is for ExistingFileHelper validation.
   Physical textures are in textures/blocks/, not textures/block/."
  [texture]
  (when texture
    (str texture)))

(defn- texture-from-spec
  [block-spec model-name]
  (or (get-in block-spec [:model-textures model-name])
      (get-in block-spec [:model-textures (keyword model-name)])
      (get-in block-spec [:textures :all])
      (get-in block-spec [:properties :model-textures model-name])
      (get-in block-spec [:properties :model-textures (keyword model-name)])
      (get-in block-spec [:properties :textures :all])
      (get-in block-spec [:properties :texture])
      (str modid/MOD-ID ":block/" model-name)))

(defn- parent-from-spec
  [block-spec]
  (or (:model-parent block-spec)
      (get-in block-spec [:properties :model-parent])
      "minecraft:block/cube_all"))

(defn- model-id->model-name
  [model-id]
  (str/replace model-id #".*:block/" ""))

(def ^:private flat-item-icon-blocks
  "Blocks that should use a flat item icon directly from block textures."
  #{"matrix" "solar_gen"})

(defn- write-flat-item-model!
  [^BlockStateProvider provider registry-name texture-rl]
  (let [item-models (.itemModels provider)
        builder (.withExistingParent item-models registry-name "item/generated")]
    (.texture builder "layer0" (parse-rl texture-rl))
    builder))

(defn- ensure-block-model!
  "Generate block model using Forge API.
   Queries business layer for texture configuration, then uses appropriate Forge API:
   - .cube() for models with custom side/vert textures (e.g., node blocks)
   - parent-driven model for explicit :model-parent definitions
   - .cubeAll() fallback for simple textured blocks"
  [^BlockStateProvider provider model-id]
  (let [model-name (model-id->model-name model-id)]
    ;; Query business layer for texture configuration
    (if-let [tex-cfg (blockstate-def/get-model-texture-config model-name)]
      ;; Special model: use .cube() with side/vert textures
      (let [side-texture (parse-rl (:side tex-cfg))
            vert-texture (parse-rl (:vert tex-cfg))
            builder (.cube (.models provider)
                          model-name
                          vert-texture   ; down
                          vert-texture   ; up
                          side-texture   ; north
                          side-texture   ; south
                          side-texture   ; east
                          side-texture)] ; west
        builder)
      ;; Default model: honor explicit DSL parent/textures first.
      (let [registry-name (infer-registry-name-from-model model-name)
            block-spec (registry-name->block-spec registry-name)
        parent (parent-from-spec block-spec)
        explicit-texture (some-> (or (get-in block-spec [:textures :all])
                     (get-in block-spec [:properties :textures :all]))
                 normalize-block-texture)]
        (if (or (not= parent "minecraft:block/cube_all") explicit-texture)
          (let [builder (.withExistingParent (.models provider)
                     model-name
                     (parse-rl parent "minecraft"))]
        (when explicit-texture
          (.texture builder "all" (parse-rl explicit-texture)))
        builder)
          (let [texture-all (parse-rl (texture-from-spec block-spec model-name))
            builder (.cubeAll (.models provider) model-name texture-all)]
        builder))))))

(defn- condition->typed
  [property-key value]
  (case property-key
    :energy (Integer/valueOf (int (if (string? value) (Integer/parseInt value) value)))
    :connected (Boolean/valueOf (str value))
    value))

(defn- apply-node-condition!
  "Apply block state condition for a block.
  Gets Property objects dynamically from blockstate-properties module."
  [part-builder block-id condition]
  (let [block-id-str (if (keyword? block-id) (name block-id) block-id)]
    (doseq [[property-key raw-value] condition]
      (if-let [property (bsp/get-property block-id-str property-key)]
      ;; Use the dynamically retrieved property object
      (.condition part-builder property
                 (into-array Comparable [(condition->typed property-key raw-value)]))
      ;; Fallback: warn if property not found
      (log/warn "Property not found for block" block-id-str ":" property-key))))
  part-builder)

(defn- build-simple-block!
  [^BlockStateProvider provider block-key definition]
  (let [registry-name (:registry-name definition)
        block (resolve-registered-block block-key registry-name)
        block-id (if (keyword? block-key) (name block-key) block-key)]
    (when (or (nil? block) (= block Blocks/AIR))
      (throw (ex-info "Simple block not resolvable for datagen"
                      {:block-key block-key :registry-name registry-name})))
    (let [model-id (first (:models (first (:parts definition))))
          model-file (ensure-block-model! provider model-id)
          block-spec (registry-name->block-spec registry-name)]
      (.simpleBlock provider block model-file)
      (when (registry-metadata/should-create-block-item? block-id)
        (if (contains? flat-item-icon-blocks registry-name)
          (let [texture-rl (texture-from-spec block-spec registry-name)]
            (write-flat-item-model! provider registry-name texture-rl))
          (.simpleBlockItem provider block model-file))))))

(defn- build-multipart-block!
  [^BlockStateProvider provider block-key definition]
  (let [registry-name (:registry-name definition)
        block (resolve-registered-block block-key registry-name)
        block-id (if (keyword? block-key) (name block-key) block-key)]
    (when (or (nil? block) (= block Blocks/AIR))
      (throw (ex-info "Multipart block not resolvable for datagen"
                      {:block-key block-key :registry-name registry-name})))
    (let [builder (.getMultipartBuilder provider block)]
      (doseq [part (:parts definition)]
        (let [model-id (first (:models part))
              model-file (ensure-block-model! provider model-id)
              part-builder (-> (.part builder)
                               (.modelFile model-file)
                               (.addModel))]
          (if-let [condition (:condition part)]
            (-> part-builder
                (apply-node-condition! block-key condition)
                (.end))
            (.end part-builder))))
      ;; For node blocks, use _base variant for item model
      (when (registry-metadata/should-create-block-item? block-id)
        (let [item-model-id (blockstate-def/get-item-model-id modid/MOD-ID registry-name)]
          (.simpleBlockItem provider block (ensure-block-model! provider item-model-id)))))))

(defn- generate-with-forge-builder!
  [^CachedOutput cache ^PackOutput pack-output ^ExistingFileHelper exfile-helper all-defs]
  (let [simple-count (atom 0)
        multipart-count (atom 0)
        ;; Defer `proxy` macroexpansion to runtime to avoid Minecraft registry
        ;; bootstrap during AOT/checkClojure/compileClojure.
        provider (eval
                   `(proxy [BlockStateProvider] [~pack-output ~modid/MOD-ID ~exfile-helper]
                      (registerStatesAndModels []
                        (doseq [[block-key definition] ~all-defs]
                          (if (blockstate-def/is-multipart-block? definition)
                            (do
                              (build-multipart-block! this block-key definition)
                              (swap! ~multipart-count inc))
                            (do
                              (build-simple-block! this block-key definition)
                              (swap! ~simple-count inc))))))]
    {:future (.run provider cache)
     :simple @simple-count
     :multipart @multipart-count}))

(defn create
  "创建BlockState DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output exfile-helper]
  (reify DataProvider
    (run [_this cache]
      ;; Datagen runs outside normal mod init order; ensure Property registry is seeded.
      (bsp/init-all-properties!)
      (let [all-defs (blockstate-def/get-all-definitions)
            {:keys [future simple multipart]} (generate-with-forge-builder!
                                               cache pack-output exfile-helper all-defs)
            written-count (+ simple multipart)]
        (println (str "[blockstate-provider] summary: defs=" (count all-defs)
                      ", simple=" simple
                      ", simple-fallback=0"
                      ", multipart=" multipart
                      ", written=" written-count))
        (CompletableFuture/allOf (into-array CompletableFuture [future]))))
    (getName [_this]
      "MyMod BlockStates (from core definitions)")))

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

