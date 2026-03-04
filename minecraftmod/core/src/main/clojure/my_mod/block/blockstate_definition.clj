(ns my-mod.block.blockstate-definition
  "BlockState定义层 - 独立于平台
   
   定义所有block的blockstate结构、属性和model对应关系。
   这些定义在core中，可被所有平台（forge, fabric）使用。
   
   每个平台的datagen实现可以根据这些定义调用对应的API生成JSON文件。")

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

(def MOD-ID "my_mod")

;; 基础blocks (简单单一model)
(def SIMPLE_BLOCKS
  {
   :matrix
   (BlockStateDefinition.
    "matrix"
    {}
    [{:condition nil :models [(str MOD-ID ":block/matrix")]}])
   
   :windgen-main
   (BlockStateDefinition.
    "windgen_main"
    {}
    [{:condition nil :models [(str MOD-ID ":block/windgen_main")]}])
   
   :windgen-pillar
   (BlockStateDefinition.
    "windgen_pillar"
    {}
    [{:condition nil :models [(str MOD-ID ":block/windgen_pillar")]}])
   
   :windgen-base
   (BlockStateDefinition.
    "windgen_base"
    {}
    [{:condition nil :models [(str MOD-ID ":block/windgen_base")]}])
   
   :windgen-fan
   (BlockStateDefinition.
    "windgen_fan"
    {}
    [{:condition nil :models [(str MOD-ID ":block/windgen_fan")]}])
   
   :solar-gen
   (BlockStateDefinition.
    "solar_gen"
    {}
    [{:condition nil :models [(str MOD-ID ":block/solar_gen")]}])
   
   :phase-gen
   (BlockStateDefinition.
    "phase_gen"
    {}
    [{:condition nil :models [(str MOD-ID ":block/phase_gen")]}])
   
   :reso-ore
   (BlockStateDefinition.
    "reso_ore"
    {}
    [{:condition nil :models [(str MOD-ID ":block/reso_ore")]}])})

;; ============================================================================
;; Node Blocks (多维度动态BlockState)
;; ============================================================================

(defn- create-node-definition
  "创建node block的multipart blockstate定义
   
   参数：
     node-type: basic, standard, advanced
   
   返回：
     BlockStateDefinition with multipart structure
     
   结构：
     - 1个base part (始终应用)
     - 5个energy overlays (根据energy值 0-4)
     - 1个connected overlay (connected=true时应用)"
  [node-type]
  (let [base-model (str MOD-ID ":block/node_" node-type "_base")
        energy-models (vec (for [level (range 5)]
                             {:condition {:energy (str level)}
                              :models [(str MOD-ID ":block/node_" node-type "_energy_" level)]}))
        connected-model {:condition {:connected "true"}
                        :models [(str MOD-ID ":block/node_" node-type "_connected")]}]
    (BlockStateDefinition.
     (str "node_" node-type)
     {:energy {:min 0 :max 4}  ; IntegerProperty
      :connected {:type :boolean}}  ; BooleanProperty
     (vec (concat
           ;; Base part - 始终应用，提供基础结构
           [{:condition nil :models [base-model]}]
           ;; Energy overlays - 根据能量等级
           energy-models
           ;; Connected overlay - 连接状态
           [connected-model])))))

(def NODE_BLOCKS
  {
   :node-basic (create-node-definition "basic")
   :node-standard (create-node-definition "standard")
   :node-advanced (create-node-definition "advanced")})

;; ============================================================================
;; 查询接口
;; ============================================================================

(defn get-block-state-definition
  "获取指定block的BlockState定义
   
   参数：
     block-key: 关键字，如 :node-basic, :matrix
   
   返回：
     BlockStateDefinition 或 nil"
  [block-key]
  (or (get SIMPLE_BLOCKS block-key)
      (get NODE_BLOCKS block-key)))

(defn get-all-definitions
  "获取所有block的BlockState定义
   
   返回：
     map of block-key -> BlockStateDefinition"
  []
  (merge SIMPLE_BLOCKS NODE_BLOCKS))

(defn get-definitions-for-platform
  "获取特定平台的block定义
   
   参数：
     platform: :forge-1.20.1, :fabric-1.20.1 等
   
   返回：
     所有该平台支持的block定义"
  [platform]
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

(comment
  ;; 使用示例
  (get-block-state-definition :node-basic)
  => #my_mod.block.blockstate_definition.BlockStateDefinition{...}
  
  (get-all-definitions)
  => {:matrix ... :node-basic ... ...}
  
  (-> :node-basic get-block-state-definition :parts)
  => [{:condition nil :models [...]} {:condition {:energy "0"} :models [...]} ...]
  )
