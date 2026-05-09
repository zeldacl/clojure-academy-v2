(ns cn.li.fabric1201.datagen.resource-location
  (:require [cn.li.mc1201.datagen.resource-location :as shared]))

(defn parse-resource-location
  [s]
  (shared/parse-resource-location s))

(defn parse-resource-location-with-default
  [s default-namespace]
  (shared/parse-resource-location s default-namespace))