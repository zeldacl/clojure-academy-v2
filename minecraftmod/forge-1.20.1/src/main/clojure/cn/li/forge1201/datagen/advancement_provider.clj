(ns cn.li.forge1201.datagen.advancement-provider
  "Generate advancement JSON from AC achievement metadata."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.advancement-provider-core :as adv-core]
            [cn.li.mc1201.datagen.item-registry :as item-registry]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
  (:import [com.google.gson Gson JsonElement]
           [java.nio.file Path]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput]))

(def ^:private ^Gson gson
  (gson-util/create-pretty-gson))

(defn- make-known-item-ids
  "Build known items set using shared helper"
  []
  (item-registry/known-item-ids
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-all-item-ids)
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-item-registry-name)
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-all-block-ids)
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-block-registry-name)
    "my_mod"))

(defn create
  [^PackOutput pack-output _exfile-helper]
  (let [out-root (.getOutputFolder pack-output)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [known (make-known-item-ids)
              tabs (datagen-metadata/get-achievement-tabs)
              all-achievements (datagen-metadata/get-achievements)
              writes (atom [])]
          (doseq [tab tabs]
            (let [root-rel (adv-core/root-path (:id tab))
              root-json* (adv-core/root-json tab)
                  root-tree (.toJsonTree gson root-json*)
                  root-path* (.resolve ^Path out-root ^String root-rel)]
              (swap! writes conj (DataProvider/saveStable cached ^JsonElement root-tree ^Path root-path*))))
          (doseq [ach all-achievements]
            (let [root-rl (adv-core/tab-root-id (:tab ach))
              rel-path (adv-core/ach-path (:id ach))
              json-map (adv-core/ach-json ach root-rl known)
                  json-tree (.toJsonTree gson json-map)
                  target-path (.resolve ^Path out-root ^String rel-path)]
              (swap! writes conj (DataProvider/saveStable cached ^JsonElement json-tree ^Path target-path))))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str modid/*mod-id* " Advancement Provider")))))

