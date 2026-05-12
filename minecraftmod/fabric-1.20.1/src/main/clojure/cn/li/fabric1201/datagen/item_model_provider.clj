(ns cn.li.fabric1201.datagen.item-model-provider
  "Fabric 1.20.1 item model datagen provider.

  Emits item model JSON files from DSL item metadata."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.item-model-provider-core :as item-model-core])
  (:import [com.google.gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn create-provider
  [output]
  (let [^String mod-id (str modid/*mod-id*)
  path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/item")
        gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [{:keys [all-item-count energy-tier-count simple-count models]} (item-model-core/gather-model-specs)
              writes (atom [])]
          (doseq [{:keys [model-name json]} models]
            (let [target-path (.json ^PackOutput$PathProvider path-provider (ResourceLocation. mod-id model-name))
                  json-tree (.toJsonTree gson (gson-util/normalize-json json))]
              (swap! writes conj
                     (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))
          (println (str "[item-model-provider/fabric] summary: items=" all-item-count
                        ", energy-tier=" energy-tier-count
                        ", simple-model=" simple-count))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str mod-id " Item Model Provider")))))
