(ns my-mod.datagen.model-gen
  "Model JSON生成器 - 为multipart blockstate创建优化的model文件"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn ensure-dir [file]
  (.mkdirs (.getParentFile file)))

(defn write-json [path data]
  (let [file (io/file path)]
    (ensure-dir file)
    (with-open [w (io/writer file)]
      (json/write data w :indent true))))

(defn get-model-path [model-name]
  (str "core/src/main/resources/assets/my_mod/models/block/" model-name ".json"))

;; Multipart model策略：
;; - Base model: 包含基础几何和静态纹理
;; - Energy overlays: 使用不同的energy纹理（可以是发光效果）
;; - Connected overlay: 改变连接指示器纹理

(defn generate-node-base-model
  "生成节点的base model（基础结构）"
  [node-type]
  {:parent "minecraft:block/cube_all"
   :textures {:all (str "my_mod:blocks/node_" node-type "_base")}})

(defn generate-node-energy-model
  "生成能量等级overlay model"
  [node-type energy-level]
  {:parent "minecraft:block/cube_all"
   :textures {:all (str "my_mod:blocks/node_" node-type "_energy_" energy-level)}})

(defn generate-node-connected-model
  "生成连接状态overlay model"
  [node-type]
  {:parent "minecraft:block/cube_all"
   :textures {:all (str "my_mod:blocks/node_" node-type "_connected")}})

(defn generate-matrix-model
  "生成matrix model"
  []
  {:parent "minecraft:block/cube_all"
   :textures {:all "my_mod:blocks/matrix"}})

(defn generate-all-models
  "生成所有优化后的model文件"
  []
  (println "开始生成model文件（multipart优化版本）...")
  
  (let [node-types ["basic" "standard" "advanced"]]
    ;; 为每种node类型生成models
    (doseq [node-type node-types]
      (println (str "\n处理 node_" node-type ":"))
      
      ;; Base model
      (let [model-name (str "node_" node-type "_base")
            path (get-model-path model-name)
            data (generate-node-base-model node-type)]
        (write-json path data)
        (println (str "  ✓ " model-name ".json (base)")))
      
      ;; Energy models (0-4)
      (doseq [energy (range 5)]
        (let [model-name (str "node_" node-type "_energy_" energy)
              path (get-model-path model-name)
              data (generate-node-energy-model node-type energy)]
          (write-json path data)
          (println (str "  ✓ " model-name ".json (energy=" energy ")"))))
      
      ;; Connected model
      (let [model-name (str "node_" node-type "_connected")
            path (get-model-path model-name)
            data (generate-node-connected-model node-type)]
        (write-json path data)
        (println (str "  ✓ " model-name ".json (connected)")))))
  
  ;; 生成matrix model
  (let [path (get-model-path "matrix")
        data (generate-matrix-model)]
    (write-json path data)
    (println "\n✓ matrix.json"))
  
  (println "\n生成完成！")
  (println "Model文件统计：")
  (println "  Node models: 3 types × (1 base + 5 energy + 1 connected) = 21 files")
  (println "  Matrix model: 1 file")
  (println "  总计: 22 files")
  (println "\n对比旧方案(30 files)，减少了 27% 的文件数量")
  (println "更重要的是：添加新维度时，增长是线性的而非乘法！"))

(comment
  ;; 运行生成器
  (generate-all-models)
  
  ;; 查看单个model结构
  (generate-node-base-model "basic")
  (generate-node-energy-model "basic" 3)
  )
