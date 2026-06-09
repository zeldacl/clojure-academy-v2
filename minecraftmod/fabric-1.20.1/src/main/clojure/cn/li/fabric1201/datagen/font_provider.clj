(ns cn.li.fabric1201.datagen.font-provider
  "Fabric 1.20.1 font provider.

  Emits font provider json from shared mc1201 datagen metadata."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.font-provider-core :as font-core]
            [cn.li.mc1201.datagen.gson-util :as gson-util])
  (:import [com.google.gson Gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn create-provider
  [^PackOutput output]
  (let [^String mod-id (str modid/*mod-id*)
        path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "font")
        ^Gson gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (font-core/save-font-files!
         (font-core/font-entries)
         (fn [file-name data]
           (let [font-id (subs file-name 0 (max 0 (- (.length file-name) 5)))
                 json-tree (.toJsonTree gson data)
                 target-path (.json ^PackOutput$PathProvider path-provider
                                    (ResourceLocation. mod-id font-id))]
             (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path)))))
      (getName [_] (str mod-id " Font Provider")))))
