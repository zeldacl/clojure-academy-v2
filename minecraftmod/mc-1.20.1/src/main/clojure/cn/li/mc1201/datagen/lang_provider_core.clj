(ns cn.li.mc1201.datagen.lang-provider-core
  "Shared language datagen helpers used by platform providers."
  (:require [cn.li.mc1201.datagen.lang-data :as lang-data]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata]))

(defn merged-language-data
  []
  (lang-data/merged-lang-data datagen-metadata/get-translation-maps))

(defn language-map
  [lang-code]
  (get (merged-language-data)
       (str lang-code ".json")
       {}))