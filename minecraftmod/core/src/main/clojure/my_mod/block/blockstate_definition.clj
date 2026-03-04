(ns my-mod.block.blockstate-definition
  "BlockState定义层 - 独立于平台
   
   定义所有block的blockstate结构、属性和model对应关系。
   这些定义在core中，可被所有平台（forge, fabric）使用。
   
  每个平台的datagen实现可以根据这些定义调用对应的API生成JSON文件。"
  (:require [my-mod.registry.metadata :as registry-metadata]
              [my-mod.block.wireless-node :as wireless-node]
              [my-mod.block.wireless-matrix]))

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
