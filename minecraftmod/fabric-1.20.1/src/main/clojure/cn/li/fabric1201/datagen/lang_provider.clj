(ns cn.li.fabric1201.datagen.lang-provider
  "Fabric 1.20.1 language provider.

  Emits translation entries from shared mc1201 datagen language data."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.lang-provider-core :as lang-core])
  (:import [com.google.gson Gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn create-provider
  [^PackOutput output
   lang-code]
  (let [^String mod-id (str modid/*mod-id*)
        path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "lang")
        ^Gson gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (lang-core/save-language-files!
         [(lang-core/language-entry lang-code)]
         (fn [_file-name data]
           (let [json-tree (.toJsonTree gson data)
                 target-path (.json ^PackOutput$PathProvider path-provider (ResourceLocation. mod-id lang-code))]
             (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path)))))
      (getName [_] (str mod-id " Language Provider " lang-code)))))
