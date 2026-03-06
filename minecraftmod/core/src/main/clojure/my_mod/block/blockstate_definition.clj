(ns my-mod.block.blockstate-definition
  "BlockState定义层 - 独立于平台
   
   定义所有block的blockstate结构、属性和model对应关系。
   这些定义在core中，可被所有平台（forge, fabric）使用。
   
  每个平台的datagen实现可以根据这些定义调用对应的API生成JSON文件。"
  (:require [clojure.string :as str]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.block.wireless-node :as wireless-node]))

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
;; 从 DSL/注册元数据自动推导，避免在此处重复维护定义。
(def SIMPLE_BLOCKS
  (into {}
        (for [block-id (registry-metadata/get-all-block-ids)
              :let [registry-name (registry-metadata/get-block-registry-name block-id)
                    simple-block? (not (.startsWith registry-name "node_"))]
              :when simple-block?]
          [(keyword block-id)
           (BlockStateDefinition.
            registry-name
            {}
            [{:condition nil
              :models [(str MOD-ID ":block/" registry-name)]}])])))

;; ============================================================================
;; Node Blocks (多维度动态BlockState)
;; 统一从 wireless_node.clj 读取定义，避免重复来源
;; ============================================================================

(def NODE_BLOCKS
  (let [energy-min (get-in wireless-node/block-state-properties [:energy :min])
        energy-max (get-in wireless-node/block-state-properties [:energy :max])
        connected-type (get-in wireless-node/block-state-properties [:connected :type])]
    (into {}
          (for [node-type (sort (keys wireless-node/node-types))
                :let [node-type-name (name node-type)
                      block-key (keyword (str "node-" node-type-name))
                      registry-name (str "node_" node-type-name)
                      base-model (str MOD-ID ":block/" registry-name "_base")
                      energy-models (vec (for [level (range energy-min (inc energy-max))]
                                           {:condition {:energy (str level)}
                                            :models [(str MOD-ID ":block/" registry-name "_energy_" level)]}))
                      connected-model {:condition {:connected "true"}
                                       :models [(str MOD-ID ":block/" registry-name "_connected")]}]]
            [block-key
             (BlockStateDefinition.
              registry-name
              {:energy {:min energy-min :max energy-max}
               :connected {:type connected-type}}
              (vec (concat
                    [{:condition nil :models [base-model]}]
                    energy-models
                    [connected-model])))]))))

;; ============================================================================
;; Node 模型名解析（与 NODE_BLOCKS 共用 wireless-node 为单一数据源）
;; ============================================================================

(defn- node-model-pattern
  "从 wireless-node 生成 node 模型名的正则，避免与 NODE_BLOCKS 重复维护 node 类型与变体。"
  []
  (let [node-type-str (str "(" (str/join "|" (map name (sort (keys wireless-node/node-types)))) ")")
        variant-str   "(base|energy_\\d+|connected)"]
    (re-pattern (str "node_" node-type-str "_" variant-str))))

(defn- parse-node-model-name
  "解析 node 模型名，返回 [node-type variant] 或 nil。
   energy 范围来自 wireless-node/block-state-properties，与 NODE_BLOCKS 一致。"
  [model-name]
  (when-let [[_ node-type variant] (re-matches (node-model-pattern) model-name)]
    [node-type variant]))

(defn- variant->energy-level
  "根据 variant 字符串得到能量等级（用于纹理）。
   范围与 wireless-node/block-state-properties 一致，与 NODE_BLOCKS 生成的 model 一致。"
  [variant]
  (let [raw (cond
              (#{"base" "connected"} variant) 0
              (str/starts-with? variant "energy_") (Integer/parseInt (subs variant 7))
              :else 0)
        {:keys [min max]} (get-in wireless-node/block-state-properties [:energy])
        min-v (or min 0)
        max-v (or max 4)]
    (clojure.core/max min-v (clojure.core/min max-v raw))))

;; ============================================================================
;; 模型纹理配置 (业务逻辑)
;; ============================================================================

(defn get-model-texture-config
  "获取模型的纹理配置
   
   此函数包含所有与模型纹理相关的业务逻辑。
   框架层调用此函数获取纹理配置，然后使用Forge API生成模型。
   
   node 的 energy/connected 变体与 NODE_BLOCKS 一致，均以 wireless-node 为单一数据源。
   
   参数：
     model-name: 模型名称（不含命名空间），如 \"node_basic_base\"
   
   返回：
     {:side \"texture-path\" :vert \"texture-path\"} - cube模型的纹理配置
     nil - 使用默认处理（cubeAll）"
  [model-name]
  (when-let [[node-type variant] (parse-node-model-name model-name)]
    (let [energy-level (variant->energy-level variant)
          top-texture  (if (= variant "connected")
                        (str MOD-ID ":block/node_top_1")
                        (str MOD-ID ":block/node_top_0"))
          side-texture (str MOD-ID ":block/node_" node-type "_side_" energy-level)]
      {:side side-texture
       :vert top-texture})))

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

(defn is-node-block?
  "判断block是否为node块（basic/standard/advanced）
   
   参数：
     registry-name: block的registry name
   
   返回：
     true 如果是node块"
  [registry-name]
  (boolean (re-find #"^node_(basic|standard|advanced)" registry-name)))

(defn get-item-model-id
  "获取block对应的物品模型ID
   
   对于node块，使用_base变体；其他块使用registry-name
   
   参数：
     mod-id: 模组ID
     registry-name: block的registry name
   
   返回：
     物品模型ID字符串"
  [mod-id registry-name]
  (if (is-node-block? registry-name)
    (str mod-id ":block/" registry-name "_base")
    (str mod-id ":block/" registry-name)))

(comment
  ;; 使用示例
  (get-block-state-definition :node-basic)
  
  (get-all-definitions)
  (keys (get-all-definitions))
  
  (-> :node-basic get-block-state-definition :parts)
  (-> :node-basic get-block-state-definition :parts first)
  )
