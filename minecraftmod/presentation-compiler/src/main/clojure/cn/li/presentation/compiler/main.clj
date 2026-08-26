(ns cn.li.presentation.compiler.main
  (:require [cn.li.presentation.compiler.artifact :as artifact]))

(defn- option [args key]
  (some (fn [[a b]] (when (= a key) b)) (partition 2 1 args)))

(defn -main [& args]
  (let [source (option args "--source")
        output (option args "--output")]
    (when-not (and source output)
      (throw (ex-info "usage: --source <dir> --output <dir>" {:args args})))
    (let [manifest (artifact/compile-directory! source output)]
      (println (format "[presentation-compiler] generated %d view artifacts"
                       (count (:views manifest)))))))