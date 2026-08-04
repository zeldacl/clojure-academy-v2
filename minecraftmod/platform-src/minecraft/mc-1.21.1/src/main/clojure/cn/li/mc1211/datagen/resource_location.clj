(ns cn.li.mc1211.datagen.resource-location
  "Loader-agnostic ResourceLocation parsing helper for datagen outputs."
  (:require [clojure.string :as str])
  (:import [net.minecraft.resources ResourceLocation]
           [cn.li.mcver ResourceLocations]))

(defn parse-resource-location
  ([s] (parse-resource-location s nil))
  ([s default-namespace]
   (let [value (str s)]
     (if (str/includes? value ":")
       (let [[namespace path] (str/split value #":" 2)]
         (ResourceLocations/of namespace path))
       (when default-namespace
         (ResourceLocations/of default-namespace value))))))