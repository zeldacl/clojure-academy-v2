(ns cn.li.forge1201.datagen.blockstate-provider
  "Forge 1.20.1 BlockState生成器 - 使用core中的定义
   
   架构：
   - core/blockstate_definition.clj: 定义block的blockstate结构（平台无关）
   - 本文件: 使用Forge/Minecraft API和定义生成JSON（平台特定）
   
   优势：定义层复用，易于支持新的Forge版本"
  (:require [cn.li.forge1201.bootstrap :as bootstrap]
            [cn.li.mcmod.config :as modid]
            [cn.li.ac.block.blockstate-definition :as blockstate-def]
            [cn.li.forge1201.datagen.resource-location :as rl]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.core Direction]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.state.properties Property]
           [net.minecraftforge.client.model.generators BlockStateProvider]
           [net.minecraftforge.client.model.generators ItemModelBuilder BlockModelBuilder ModelFile]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.registries RegistryObject]
           [java.util.concurrent CompletableFuture]))


(defn- parse-rl
  ([s] (parse-rl s modid/*mod-id*))
  ([s default-namespace]
   (rl/parse-resource-location s default-namespace)))

(defn- normalize-candidates
  [s]
  (when (and s (not (str/blank? s)))
    [s
     (str/replace s "-" "_")
     (str/replace s "_" "-")]))

(defn- registry-object->block
  [registry-object]
  (try
    (when (and registry-object (.isPresent ^RegistryObject registry-object))
      (.get ^RegistryObject registry-object))
    (catch Throwable _
      nil)))

(defn- resolve-from-registered-map
  [block-key registry-name]
  (let [registered-blocks-var (try
                                (requiring-resolve 'cn.li.forge1201.mod/registered-blocks)
                                (catch Throwable _ nil))
        registered-blocks-map (when registered-blocks-var
                                @(var-get registered-blocks-var))
        key-name (name block-key)
        candidates (distinct (concat (normalize-candidates key-name)
                                     (normalize-candidates registry-name)))]
    (when registered-blocks-map
      (some (fn [candidate]
              (when-let [registry-object (get registered-blocks-map candidate)]
                (registry-object->block registry-object)))
            candidates))))

;; Blockstate definition is now provided by mcmod (no ac dependency).

;; Dynamic counters used inside `eval`ed code to avoid embedding Atom objects
;; in the generated form (which Clojure refuses to compile).
(def ^:dynamic *datagen-simple-count* nil)
(def ^:dynamic *datagen-multipart-count* nil)

;; Also used to avoid embedding Java objects (PackOutput / ExistingFileHelper)
;; into the `eval`ed form. Clojure refuses to compile those as literals.
(def ^:dynamic *datagen-pack-output* nil)
(def ^:dynamic *datagen-exfile-helper* nil)

(defn- eval-with-source
  [form]
  (eval (with-meta form {:file "cn/li/forge1201/datagen/blockstate_provider.clj" :line 1})))

(defn- resolve-from-forge-registry
  [block-key registry-name]
  (let [key-name (name block-key)
        candidates (distinct (concat (normalize-candidates registry-name)
                                     (normalize-candidates key-name)))]
    (some (fn [candidate]
          (bootstrap/find-block modid/*mod-id* candidate))
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

(def ^:private texture-aliases
  {"my_mod:block/wind_gen_main" "my_mod:block/windgen_main"
   "my_mod:block/wind_gen_base" "my_mod:block/windgen_base"
   "my_mod:block/wind_gen_pillar" "my_mod:block/windgen_pillar"
  "my_mod:block/converter_rf_input" "my_mod:block/rf_input"
  "my_mod:block/converter_rf_output" "my_mod:block/rf_output"
  "my_mod:block/converter_eu_input" "my_mod:block/eu_input"
  "my_mod:block/converter_eu_output" "my_mod:block/eu_output"
   "my_mod:block/constrained_ore" "my_mod:block/constraint_metal"
   "my_mod:block/imaginary_ore" "my_mod:block/imagsil_ore"
   "my_mod:block/developer_normal" "my_mod:block/dev_normal"
   "my_mod:block/developer_advanced" "my_mod:block/dev_advanced"
   "my_mod:block/developer_normal_part" "my_mod:block/dev_normal"
   "my_mod:block/developer_advanced_part" "my_mod:block/dev_advanced"
   "my_mod:block/ability_interferer" "my_mod:block/ability_interf_off"
   "my_mod:block/imag_fusor" "my_mod:block/machine_frame"
   "my_mod:block/metal_former" "my_mod:block/metal_former_front"})

(defn- normalize-texture-id
  [texture]
  (when-let [normalized (normalize-block-texture texture)]
    (get texture-aliases normalized normalized)))

(defn- ensure-texture-exists!
  [texture]
  (let [texture-id (normalize-texture-id texture)
  ^ResourceLocation texture-rl (parse-rl texture-id)
        resource-path (str "assets/" (.getNamespace texture-rl)
                           "/textures/" (.getPath texture-rl) ".png")]
    (when-not (io/resource resource-path)
      (throw (ex-info "Datagen texture missing after alias resolution"
                      {:requested texture
                       :resolved texture-id
                       :resource-path resource-path})))
    texture-rl))

(defn- texture-from-spec
  [block-spec model-name]
  (or (get-in block-spec [:model-textures model-name])
      (get-in block-spec [:model-textures (keyword model-name)])
      (get-in block-spec [:textures :all])
      (get-in block-spec [:properties :model-textures model-name])
      (get-in block-spec [:properties :model-textures (keyword model-name)])
      (get-in block-spec [:properties :textures :all])
      (get-in block-spec [:properties :texture])
      (str modid/*mod-id* ":block/" model-name)))

(defn- parent-from-spec
  [block-spec]
  (or (get-in block-spec [:rendering :model-parent])
      (get-in block-spec [:properties :model-parent])
      "minecraft:block/cube_all"))

(defn- model-id->model-name
  [model-id]
  (str/replace model-id #".*:block/" ""))

(defn- write-flat-item-model!
  [^BlockStateProvider provider ^String registry-name texture-rl]
  (let [item-models (.itemModels provider)
    ^ItemModelBuilder builder (.withExistingParent item-models registry-name "item/generated")
    ^ResourceLocation texture-loc (ensure-texture-exists! texture-rl)]
  (.texture builder "layer0" texture-loc)
    builder))

(defn- invoke-part-condition!
  [part-builder property typed-values]
  (let [m (.getMethod (class part-builder) "condition" (into-array Class [Property (class typed-values)]))]
    (.invoke m part-builder (object-array [property typed-values]))
    part-builder))

(defn- end-part-builder!
  [part-builder]
  (let [m (.getMethod (class part-builder) "end" (into-array Class []))]
    (.invoke m part-builder (object-array []))))

(defn- ensure-block-model!
  "Generate block model using Forge API.
   Queries business layer for texture configuration, then uses appropriate Forge API:
   - .cube() for models with custom side/vert textures (e.g., node blocks)
   - parent-driven model for explicit :model-parent definitions
   - .cubeAll() fallback for simple textured blocks"
  [^BlockStateProvider provider model-id]
  (let [^String model-name (model-id->model-name model-id)]
    ;; Query business layer for texture configuration
    (if-let [cube-tex (blockstate-def/get-model-cube-texture-config model-name)]
      (let [^ResourceLocation down-texture (ensure-texture-exists! (:down cube-tex))
            ^ResourceLocation up-texture (ensure-texture-exists! (:up cube-tex))
            ^ResourceLocation north-texture (ensure-texture-exists! (:north cube-tex))
            ^ResourceLocation south-texture (ensure-texture-exists! (:south cube-tex))
            ^ResourceLocation east-texture (ensure-texture-exists! (:east cube-tex))
            ^ResourceLocation west-texture (ensure-texture-exists! (:west cube-tex))]
        (.cube (.models provider)
               model-name
               down-texture
               up-texture
               north-texture
               south-texture
               east-texture
               west-texture))
      (if-let [tex-cfg (blockstate-def/get-model-texture-config model-name)]
        ;; Special model: use .cube() with side/vert textures
        (let [^ResourceLocation side-texture (parse-rl (:side tex-cfg))
              ^ResourceLocation vert-texture (parse-rl (:vert tex-cfg))
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
        (let [^String registry-name (infer-registry-name-from-model model-name)
              block-spec (registry-name->block-spec registry-name)
              ^String parent (parent-from-spec block-spec)
              ^String explicit-texture (some-> (or (get-in block-spec [:rendering :textures :all])
                                                   (get-in block-spec [:properties :textures :all]))
                                         normalize-block-texture)]
          (if (or (not= parent "minecraft:block/cube_all") explicit-texture)
            (let [^ResourceLocation parent-rl (parse-rl parent "minecraft")
                  ^BlockModelBuilder builder (.withExistingParent (.models provider)
                                              model-name
                                              parent-rl)]
              (when explicit-texture
                (let [^ResourceLocation tex-rl (ensure-texture-exists! explicit-texture)]
                  (.texture builder "all" tex-rl)))
              builder)
            (let [^ResourceLocation texture-all (ensure-texture-exists! (texture-from-spec block-spec model-name))
                  builder (.cubeAll (.models provider) model-name texture-all)]
              builder)))))))

(defn- parse-facing
  [value]
  (let [raw (if (keyword? value) (name value) (str value))]
    (or (Direction/byName (str/lower-case raw))
        Direction/NORTH)))

(defn- condition->typed
  [property-key value]
  (case property-key
    :energy (Integer/valueOf (int (if (string? value) (Integer/parseInt value) value)))
    :frame (Integer/valueOf (int (if (string? value) (Integer/parseInt value) value)))
    :facing (parse-facing value)
    :on (Boolean/valueOf (str value))
    :connected (Boolean/valueOf (str value))
    value))

(defn- apply-node-condition!
  "Apply block state condition for a block.
  Gets Property objects dynamically from blockstate-properties module."
  [part-builder block-id condition]
  (let [block-id-str (if (keyword? block-id) (name block-id) block-id)
        get-property (requiring-resolve 'cn.li.forge1201.blockstate-properties/get-property)]
    (doseq [[property-key raw-value] condition]
      (if-let [property (get-property block-id-str property-key)]
      ;; Use the dynamically retrieved property object
        (invoke-part-condition! part-builder property
                    (into-array Comparable [(condition->typed property-key raw-value)]))
      ;; Fallback: warn if property not found
      (log/warn "Property not found for block" block-id-str ":" property-key))))
  part-builder)

(defn build-simple-block!
  [^BlockStateProvider provider block-key definition]
  (let [registry-name (:registry-name definition)
      ^Block block (resolve-registered-block block-key registry-name)
        block-id (if (keyword? block-key) (name block-key) block-key)]
    (when (bootstrap/air-block? block (bootstrap/get-air-block))
      (throw (ex-info "Simple block not resolvable for datagen"
                      {:block-key block-key :registry-name registry-name})))
    (let [model-id (first (:models (first (:parts definition))))
          ^ModelFile model-file (ensure-block-model! provider model-id)
          block-spec (registry-name->block-spec registry-name)]
      (.simpleBlock provider block model-file)
      (when (registry-metadata/should-create-block-item? block-id)
        ;; Query metadata instead of hardcoded set
        (if (get-in block-spec [:rendering :flat-item-icon?])
          (let [texture-rl (texture-from-spec block-spec registry-name)]
            (write-flat-item-model! provider registry-name texture-rl))
          (.simpleBlockItem provider block model-file))))))

(defn build-multipart-block!
  [^BlockStateProvider provider block-key definition]
  (let [registry-name (:registry-name definition)
      ^Block block (resolve-registered-block block-key registry-name)
        block-id (if (keyword? block-key) (name block-key) block-key)]
    (when (bootstrap/air-block? block (bootstrap/get-air-block))
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
              (end-part-builder!))
            (end-part-builder! part-builder))))
      ;; For node blocks, use _base variant for item model
      (when (registry-metadata/should-create-block-item? block-id)
        (let [item-model-id (blockstate-def/get-item-model-id modid/*mod-id* registry-name)]
          (.simpleBlockItem provider block (ensure-block-model! provider item-model-id)))))))

(defn- generate-with-forge-builder!
  [^CachedOutput cache ^PackOutput pack-output ^ExistingFileHelper exfile-helper all-defs]
  (let [simple-count (atom 0)
        multipart-count (atom 0)
        ;; Defer `proxy` macroexpansion to runtime to avoid Minecraft registry
        ;; bootstrap during AOT/checkClojure/compileClojure.
        ;;
        ;; Important: do NOT embed Java objects (PackOutput/ExistingFileHelper) into the
        ;; evaled form. Clojure refuses to compile those literals. Instead, bind them
        ;; to dynamic vars and reference the vars in the generated proxy call.
        ^BlockStateProvider provider
        (binding [*datagen-pack-output* pack-output
                  *datagen-exfile-helper* exfile-helper]
          (eval-with-source
           `(proxy [BlockStateProvider] [*datagen-pack-output* ~modid/*mod-id* *datagen-exfile-helper*]
              (registerStatesAndModels []
                (doseq [[block-key# definition#] ~all-defs]
                  (if (blockstate-def/is-multipart-block? definition#)
                    (do
                      (cn.li.forge1201.datagen.blockstate-provider/build-multipart-block!
                       ~'this block-key# definition#)
                      (swap! *datagen-multipart-count* inc))
                    (do
                      (cn.li.forge1201.datagen.blockstate-provider/build-simple-block!
                       ~'this block-key# definition#)
                      (swap! *datagen-simple-count* inc))))))))]
    {:future (binding [*datagen-simple-count* simple-count
                       *datagen-multipart-count* multipart-count]
               (.run provider cache))
     :simple @simple-count
     :multipart @multipart-count}))

(defn create
  "创建BlockState DataProvider实例 (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output exfile-helper]
  (reify DataProvider
    (run [_this cache]
      ;; Datagen runs outside normal mod init order; ensure Property registry is seeded.
      (when-let [init-all-properties! (requiring-resolve 'cn.li.forge1201.blockstate-properties/init-all-properties!)]
        (init-all-properties!))
      (let [all-defs (blockstate-def/get-all-definitions)
            {:keys [future simple multipart]} (generate-with-forge-builder!
                                               cache pack-output exfile-helper all-defs)
            written-count (+ simple multipart)]
        (println (str "[blockstate-provider] summary: defs=" (count all-defs)
                      ", simple=" simple
                      ", simple-fallback=0"
                      ", multipart=" multipart
                      ", written=" written-count))
        (CompletableFuture/allOf ^"[Ljava.util.concurrent.CompletableFuture;"
               (into-array CompletableFuture [future]))))
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

