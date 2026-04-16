(ns cn.li.ac.block.blockstate-definition
  "BlockState定义层 - 独立于平台

   定义所有block的blockstate结构、属性和model对应关系。
   这些定义在core中，可被所有平台（forge, fabric）使用。

  每个平台的datagen实现可以根据这些定义调用对应的API生成JSON文件。

  Node-specific blockstate logic has been extracted to wireless-node/blockstate.clj
  to colocate it with the node block implementation."
  (:require [cn.li.mcmod.config :as mcmod-config]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
             [cn.li.ac.block.wireless-node.blockstate :as node-blockstate]))

;; ============================================================================
;; BlockState部分定义
;; ============================================================================

(defrecord BlockStatePart
  [;; 应用此part的条件 (map of property-name -> property-value)
   ;; nil表示无条件（始终应用）
   condition
   ;; 要应用的model列表 (通常是一个model，但支持多个用于特殊情况)
   models])

(defrecord BlockStateDefinition
  [;; block的minecraft registry name (不含命名空间)
   registry-name
   ;; BlockState属性定义
   properties
   ;; multipart格式的部分列表
   parts])

;; ============================================================================
;; Block定义常量
;; ============================================================================

(defn- mod-id []
  ;; Use centralized config so Forge/Fabric and datagen share the same namespace.
  mcmod-config/*mod-id*)

(def ^:private imag-fusor-facings ["north" "east" "south" "west"])
(def ^:private metal-former-facings ["north" "east" "south" "west"])

(defn- imag-fusor-front-texture
  [frame]
  (if (zero? (int frame))
    (str (mod-id) ":block/ief_off")
    (str (mod-id) ":block/ief_working_" (int frame))))

(defn- imag-fusor-model-name
  [facing frame]
  (str "imag_fusor_" facing "_frame_" frame))

(defn- imag-fusor-model-id
  [facing frame]
  (str (mod-id) ":block/" (imag-fusor-model-name facing frame)))

(defn- metal-former-model-name
  [facing]
  (str "metal_former_" facing))

(defn- metal-former-model-id
  [facing]
  (str (mod-id) ":block/" (metal-former-model-name facing)))

(defn- imag-fusor-parts
  []
  (vec
    (for [facing imag-fusor-facings
          frame (range 0 5)]
      {:condition {:facing facing :frame frame}
       :models [(imag-fusor-model-id facing frame)]})))

(defn- metal-former-parts
  []
  (vec
    (for [facing metal-former-facings]
      {:condition {:facing facing}
       :models [(metal-former-model-id facing)]})))

(defn- parse-imag-fusor-model-name
  [model-name]
  (when-let [[_ facing frame] (re-matches #"imag_fusor_(north|east|south|west)_frame_([0-4])" model-name)]
    {:facing facing
     :frame (Integer/parseInt frame)}))

(defn- parse-metal-former-model-name
  [model-name]
  (when-let [[_ facing] (re-matches #"metal_former_(north|east|south|west)" model-name)]
    {:facing facing}))

(defn get-model-cube-texture-config
  "Get explicit per-face cube textures for generated models.

   Returns nil when model uses default handling."
  [model-name]
  (or
    (when-let [{:keys [facing frame]} (parse-imag-fusor-model-name model-name)]
      (let [front (imag-fusor-front-texture frame)
            side (str (mod-id) ":block/machine_side")
            top (str (mod-id) ":block/machine_top")
            down (str (mod-id) ":block/machine_bottom")
            sides (case facing
                    "north" {:north front :south side :east side :west side}
                    "south" {:north side :south front :east side :west side}
                    "east" {:north side :south side :east front :west side}
                    "west" {:north side :south side :east side :west front}
                    {:north front :south side :east side :west side})]
        (merge {:down down :up top} sides)))
    (when-let [{:keys [facing]} (parse-metal-former-model-name model-name)]
      (let [front (str (mod-id) ":block/metal_former_front")
            back (str (mod-id) ":block/metal_former_back")
            right (str (mod-id) ":block/metal_former_right")
            left (str (mod-id) ":block/metal_former_left")
            top (str (mod-id) ":block/metal_former_top")
            down (str (mod-id) ":block/metal_former_bottom")
            sides (case facing
                    "north" {:north front :south back :east right :west left}
                    "south" {:north back :south front :east left :west right}
                    "east" {:north left :south right :east front :west back}
                    "west" {:north right :south left :east back :west front}
                    {:north front :south back :east right :west left})]
        (merge {:down down :up top} sides)))))
;; 基础blocks (简单单一model)
;; 从 DSL/注册元数据自动推导，避免在此处重复维护定义。
;; 简单block = 所有未在复杂blockstate定义中出现的block
(defn- simple-blocks
  "Compute simple (single-model) blockstate definitions from current DSL metadata.

  Important: this must be computed dynamically, because DSL registries are filled
  during runtime content load (datagen and normal game init), not at namespace
  load time."
  []
  (let [complex-block-keys (set (concat
                                 (keys (node-blockstate/get-all-node-definitions))
                                 [:ability-interferer :imag-fusor :metal-former]))]
    (into {}
          (for [block-id (registry-metadata/get-all-block-ids)
                :let [block-key (keyword block-id)
                      registry-name (registry-metadata/get-block-registry-name block-id)]
                :when (not (contains? complex-block-keys block-key))]
            [block-key
             (BlockStateDefinition.
              registry-name
              {}
              [{:condition nil
                :models [(str (mod-id) ":block/" registry-name)]}])]))))

(def COMPLEX_BLOCKS
  {:ability-interferer
   (BlockStateDefinition.
     "ability_interferer"
     {:on {:name "on" :type :boolean :default false}}
     [{:condition {:on true}
       :models [(str (mod-id) ":block/ability_interferer")]}
      {:condition {:on false}
         :models [(str (mod-id) ":block/ability_interf_off")]}])

   :imag-fusor
   (BlockStateDefinition.
     "imag_fusor"
     {:facing {:name "facing" :type :horizontal-facing :default "north"}
      :frame {:name "frame" :type :integer :min 0 :max 4 :default 0}}
     (imag-fusor-parts))

   :metal-former
   (BlockStateDefinition.
     "metal_former"
     {:facing {:name "facing" :type :horizontal-facing :default "north"}}
     (metal-former-parts))})

;; ============================================================================
;; Node Blocks - Delegated to wireless-node/blockstate.clj
;; ============================================================================
;; Node-specific blockstate definitions have been moved to
;; cn.li.ac.block.wireless-node.blockstate to colocate them with
;; the node block implementation.

;; ============================================================================
;; Node Model Parsing - Delegated to wireless-node/blockstate.clj
;; ============================================================================
;; Node model name parsing logic has been moved to
;; cn.li.ac.block.wireless-node.blockstate

;; ============================================================================
;; 模型纹理配置 (业务逻辑)
;; ============================================================================

(defn get-model-texture-config
  "获取模型的纹理配置

   此函数包含所有与模型纹理相关的业务逻辑。
   框架层调用此函数获取纹理配置，然后使用Forge API生成模型。

   Node-specific texture logic is delegated to wireless-node/blockstate.clj

   参数：
     model-name: 模型名称（不含命名空间），如 \"node_basic_base\"

   返回：
     {:side \"texture-path\" :vert \"texture-path\"} - cube模型的纹理配置
     nil - 使用默认处理（cubeAll）"
  [model-name]
  ;; Delegate to node-specific logic if it's a node model
  (or (node-blockstate/get-node-model-texture-config model-name)
      ;; Simple blocks use default cubeAll (return nil)
      nil))

;; ============================================================================
;; 查询接口
;; ============================================================================

(defn get-block-state-definition
  "获取指定block的BlockState定义

   参数：
     block-key: 关键字，如 :wireless-node-basic, :wireless-matrix

   返回：
     BlockStateDefinition 或 nil"
  [block-key]
  (or (get COMPLEX_BLOCKS block-key)
      (get (simple-blocks) block-key)
      (node-blockstate/get-node-blockstate-definition block-key)))

(defn get-all-definitions
  "获取所有block的BlockState定义

   返回：
     map of block-key -> BlockStateDefinition"
  []
  (merge (simple-blocks) COMPLEX_BLOCKS (node-blockstate/get-all-node-definitions)))

(defn get-definitions-for-platform
  "获取特定平台的block定义
   
   参数：
     platform: :forge-1.20.1, :fabric-1.20.1 等
   
   返回：
     所有该平台支持的block定义"
  [_platform]
  ;; 对于现在，所有平台支持所有block
  ;; 未来可以根据平台做过滤
  (get-all-definitions))

(defn is-multipart-block?
  "判断是否为multipart blockstate
   
   参数：
     definition: BlockStateDefinition
   
   返回：
     true 如果有多个parts或条件存在"
  [definition]
  (> (count (:parts definition)) 1))

(defn is-node-block?
  "判断block是否为node块（basic/standard/advanced）

   Delegates to wireless-node/blockstate.clj

   参数：
     registry-name: block的registry name

   返回：
     true 如果是node块"
  [registry-name]
  (node-blockstate/is-node-block? registry-name))

(defn get-item-model-id
  "获取block对应的物品模型ID

   对于node块，使用_base变体；其他块使用registry-name

   参数：
     mod-id: 模组ID
     registry-name: block的registry name

   返回：
     物品模型ID字符串"
  [mod-id registry-name]
  (cond
    (node-blockstate/is-node-block? registry-name)
    (node-blockstate/get-node-item-model-id mod-id registry-name)
    (= registry-name "imag_fusor")
    (str mod-id ":block/" (imag-fusor-model-name "north" 0))
    :else
    (str mod-id ":block/" registry-name)))

(comment
  ;; 使用示例
  (get-block-state-definition :wireless-node-basic)
  
  (get-all-definitions)
  (keys (get-all-definitions))
  
  (-> :wireless-node-basic get-block-state-definition :parts)
  (-> :wireless-node-basic get-block-state-definition :parts first)
  )
