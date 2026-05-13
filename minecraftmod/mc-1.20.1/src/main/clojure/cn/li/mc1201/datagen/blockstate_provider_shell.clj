(ns cn.li.mc1201.datagen.blockstate-provider-shell
  "Shared DataProvider shell for blockstate/block-model/item-model datagen."
  (:require [cn.li.mc1201.datagen.blockstate-provider-core :as blockstate-core]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.gson-util :as gson-util])
  (:import [net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]
           [com.google.gson Gson JsonElement]
           [java.util.concurrent CompletableFuture]))

(def ^:private ^Gson gson (gson-util/create-pretty-gson))

(defn- parse-rl
  [s]
  (rl/parse-resource-location s))

(defn- write-json!
  [^CachedOutput cached-output ^PackOutput$PathProvider path-provider ^ResourceLocation id payload]
  (DataProvider/saveStable
   cached-output
   ^JsonElement (.toJsonTree gson (gson-util/normalize-json payload))
   (.json path-provider id)))

(defn create-provider
  [^PackOutput output provider-name]
  (let [blockstate-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "blockstates")
        block-model-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/block")
        item-model-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/item")]
    (reify DataProvider
      (^CompletableFuture run [_this ^CachedOutput cached-output]
        (let [block-writes (for [{:keys [path-key id payload]} (blockstate-core/blockstate-write-entries)
                                 :let [rl-id (parse-rl id)]]
                             (case path-key
                               :blockstate (write-json! cached-output blockstate-path-provider rl-id payload)
                               :block-model (write-json! cached-output block-model-path-provider rl-id payload)
                               :item-model (write-json! cached-output item-model-path-provider rl-id payload)
                               (throw (ex-info "Unknown blockstate write path" {:path-key path-key :id id}))))]
          (CompletableFuture/allOf
           (into-array CompletableFuture block-writes))))

      (getName [_this]
        provider-name))))
