(ns cn.li.mc1201.datagen.font-provider-core
  "Shared font json datagen helpers for loader-specific providers.

  Font definitions live in mcmod.datagen.metadata (:fonts) and are populated by
  content modules (AC registers ac_normal baseline)."
  (:require [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
  (:import [java.util.concurrent CompletableFuture]))

(defn font-definitions
  []
  (datagen-metadata/get-fonts))

(defn build-font-json
  [{:keys [providers]}]
  {:providers (vec providers)})

(defn font-entries
  []
  (mapv (fn [{:keys [id] :as font}]
          [(str id ".json")
           (gson-util/normalize-json (build-font-json font))])
        (font-definitions)))

(defn save-font-files!
  "Emit font json save futures through `emit-entry!`, which receives
  `[file-name data]` pairs and must return a CompletableFuture."
  [entries emit-entry!]
  (let [writes (mapv (fn [[file-name data]]
                       (emit-entry! file-name data))
                     entries)]
    (CompletableFuture/allOf (into-array CompletableFuture writes))))
