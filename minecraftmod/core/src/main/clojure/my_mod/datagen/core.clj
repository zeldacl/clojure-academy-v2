(ns my-mod.datagen.core
  "数据生成主入口 - 生成所有blockstate和model JSON文件"
  (:require [my-mod.datagen.blockstate-gen :as blockstate-gen]
            [my-mod.datagen.model-gen :as model-gen]))

(defn generate-all
  "运行所有数据生成器"
  []
  (println "=" (apply str (repeat 60 "=")))
  (println "Minecraft Mod 数据生成器")
  (println "使用 multipart 格式实现资源线性增长")
  (println "=" (apply str (repeat 60 "=")))
  (println)
  
  ;; 生成models
  (model-gen/generate-all-models)
  
  (println)
  (println (apply str (repeat 60 "-")))
  (println)
  
  ;; 生成blockstates
  (blockstate-gen/generate-all-blockstates)
  
  (println)
  (println "=" (apply str (repeat 60 "=")))
  (println "数据生成完成！")
  (println "=" (apply str (repeat 60 "="))))

(defn -main [& args]
  (generate-all))

(comment
  ;; 在REPL中运行
  (generate-all)
  )
