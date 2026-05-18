(ns cn.li.fabric1201.datagen.recipe-provider
  "Fabric 1.20.1 recipe datagen provider.

  Emits recipe JSON files from shared platform-neutral recipe metadata."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.recipe-core :as recipe-core])
    (:import [com.google.gson Gson JsonElement]
           [java.util.concurrent CompletableFuture]
         [net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn create-provider
    [^PackOutput output]
  (let [^String mod-id (str modid/*mod-id*)
      path-provider (.createPathProvider output PackOutput$Target/DATA_PACK "recipes")
      ^Gson gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [recipes (recipe-core/load-recipes)
          writes (atom [])]
          (doseq [recipe recipes]
        (let [recipe-id (recipe-core/normalize-recipe-id (:id recipe))
                  target-path (.json ^PackOutput$PathProvider path-provider (ResourceLocation. mod-id recipe-id))
          json-tree (.toJsonTree gson (gson-util/normalize-json (recipe-core/recipe-json recipe)))]
              (swap! writes conj
                     (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str mod-id " Recipe Provider")))))
