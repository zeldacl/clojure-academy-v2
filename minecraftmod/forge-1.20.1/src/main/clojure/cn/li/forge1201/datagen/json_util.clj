(ns cn.li.forge1201.datagen.json-util
  "Compatibility wrapper for shared datagen JSON utilities."
  (:require [cn.li.mc1201.datagen.json-util :as shared]))

(defn write-json
  [x]
  (shared/write-json x))
