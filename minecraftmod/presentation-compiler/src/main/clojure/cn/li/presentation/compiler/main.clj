(ns cn.li.presentation.compiler.main
  (:require [cn.li.presentation.compiler.artifact :as artifact]))

(defn- option [args key]
  (some (fn [[a b]] (when (= a key) b)) (partition 2 1 args)))

(defn -main [& args]
  (let [source (option args "--source")
        output (option args "--output")
        content-id (option args "--content-id")]
    (when-not (and source output content-id)
      (throw (ex-info "usage: --source <dir> --output <dir> --content-id <id>" {:args args})))
    (let [manifest (artifact/compile-directory! source output content-id)]
      (println (format "[presentation-compiler] generated %d view artifacts for %s"
                       (count (:views manifest)) content-id)))))
