(ns cn.li.forge1201.datagen.resource-location
  (:require [clojure.string :as str])
  (:import [net.minecraft.resources ResourceLocation]))

(defn parse-resource-location
  ([s] (parse-resource-location s nil))
  ([s default-namespace]
   (let [value (str s)]
     (if (str/includes? value ":")
       (let [[namespace path] (str/split value #":" 2)]
         (ResourceLocation. namespace path))
       (when default-namespace
         (ResourceLocation. default-namespace value))))))
