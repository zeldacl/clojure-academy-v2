(ns cn.li.ac.block.blockstate-definition
  "BlockState定义层 - 独立于平台

   定义所有block的blockstate结构、属性和model对应关系。
   这些定义在core中，可被所有平台（forge, fabric）使用。

  每个平台的datagen实现可以根据这些定义调用对应的API生成JSON文件。

  Node-specific blockstate logic has been extracted to wireless-node/blockstate.clj
  to colocate it with the node block implementation."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as mcmod-config]
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
              :models [(str (mod-id) ":block/" registry-name)]}])])))

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
  (or (get SIMPLE_BLOCKS block-key)
      (node-blockstate/get-node-blockstate-definition block-key)))

(defn get-all-definitions
  "获取所有block的BlockState定义

   返回：
     map of block-key -> BlockStateDefinition"
  []
  (merge SIMPLE_BLOCKS (node-blockstate/get-all-node-definitions)))

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
  (if (node-blockstate/is-node-block? registry-name)
    (node-blockstate/get-node-item-model-id mod-id registry-name)
    (str mod-id ":block/" registry-name)))

(comment
  ;; 使用示例
  (get-block-state-definition :wireless-node-basic)
  
  (get-all-definitions)
  (keys (get-all-definitions))
  
  (-> :wireless-node-basic get-block-state-definition :parts)
  (-> :wireless-node-basic get-block-state-definition :parts first)
  )
