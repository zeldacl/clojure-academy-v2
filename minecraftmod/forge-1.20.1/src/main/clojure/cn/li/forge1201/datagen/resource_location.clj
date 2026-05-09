(ns cn.li.forge1201.datagen.resource-location
  "Compatibility wrapper for shared datagen resource-location helpers."
  (:require [cn.li.mc1201.datagen.resource-location :as shared]))

(defn parse-resource-location
  ([s] (shared/parse-resource-location s))
  ([s default-namespace]
   (shared/parse-resource-location s default-namespace)))
