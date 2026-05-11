(ns cn.li.fabric1201.datagen.lang-provider
  "Fabric 1.20.1 language provider.

  Emits translation entries from shared mc1201 datagen language data."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.lang-provider-core :as lang-core])
  (:import [com.google.gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn- language-map
  [lang-code]
  (lang-core/language-map lang-code))

(defn create-provider
  [output
   lang-code]
  (let [^String mod-id (str modid/*mod-id*)
      path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "lang")
        gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [writes (atom [])
          json-tree (.toJsonTree gson (language-map lang-code))
          target-path (.json ^PackOutput$PathProvider path-provider (ResourceLocation. mod-id lang-code))]
          (swap! writes conj (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str mod-id " Language Provider " lang-code)))))
