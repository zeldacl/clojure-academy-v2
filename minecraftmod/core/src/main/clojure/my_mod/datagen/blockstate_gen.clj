(ns my-mod.datagen.blockstate-gen
  "BlockState JSON生成器 - 使用multipart格式实现线性增长"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn ensure-dir [file]
  (.mkdirs (.getParentFile file)))

(defn write-json [path data]
  (let [file (io/file path)]
    (ensure-dir file)
    (with-open [w (io/writer file)]
      (json/write data w :indent true))))

;; Multipart blockstate结构
;; 优势：N个energy states + M个connected states = N+M+1个models
;; 而不是 N×M 个models

(defn generate-node-multipart-blockstate
  "生成节点的multipart blockstate
   使用线性增长策略：
   - 1个base model（始终应用）
   - 5个energy models（根据energy属性）
   - 1个connected overlay（根据connected=true）"
  [node-type]
  (let [base-model (str "my_mod:block/node_" node-type "_base")
        energy-models (map #(hash-map
                              :when {:energy (str %)}
                              :apply {:model (str "my_mod:block/node_" node-type "_energy_" %)})
                           (range 5))
        connected-model {:when {:connected "true"}
                        :apply {:model (str "my_mod:block/node_" node-type "_connected")}}]
    {:multipart (vec (concat
                      ;; Base model - 始终应用
                      [{:apply {:model base-model}}]
                      ;; Energy overlays - 根据energy值
                      energy-models
                      ;; Connected overlay - 仅当connected=true
                      [connected-model]))}))

(defn generate-matrix-blockstate
  "生成matrix的简单blockstate"
  []
  {:variants {"" {:model "my_mod:block/matrix"}}})

(defn get-blockstate-path [block-name]
  (str "core/src/main/resources/assets/my_mod/blockstates/" block-name ".json"))

(defn generate-all-blockstates
  "生成所有blockstate JSON文件"
  []
  (println "开始生成blockstate文件...")
  
  ;; 生成node blockstates (multipart格式)
  (doseq [node-type ["basic" "standard" "advanced"]]
    (let [block-name (str "node_" node-type)
          path (get-blockstate-path block-name)
          data (generate-node-multipart-blockstate node-type)]
      (write-json path data)
      (println (str "✓ 生成 " block-name ".json (multipart格式)"))))
  
  ;; 生成matrix blockstate
  (let [data (generate-matrix-blockstate)
        path (get-blockstate-path "matrix")]
    (write-json path data)
    (println "✓ 生成 matrix.json"))
  
  (println "\n生成完成！")
  (println "资源复杂度对比：")
  (println "  旧方案(variants): 3 types × 5 energy × 2 connected = 30 models")
  (println "  新方案(multipart): 3 types × (1 base + 5 energy + 1 connected) = 21 models")
  (println "  节省: 30% 的model文件"))

(comment
  ;; 运行生成器
  (generate-all-blockstates)
  
  ;; 查看单个blockstate结构
  (generate-node-multipart-blockstate "basic")
  )
