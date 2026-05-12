(ns cn.li.fabric1201.datagen.blockstate-provider
  "Fabric BlockState/BlockModel/ItemModel datagen provider.

  Generates JSON from shared hook-driven blockstate definitions and registry metadata.")

(require '[cn.li.mc1201.datagen.blockstate-provider-core :as blockstate-core])
(require '[cn.li.mc1201.datagen.resource-location :as rl])
(require '[cn.li.mc1201.datagen.gson-util :as gson-util])

(import '[net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target])
(import '[net.minecraft.resources ResourceLocation])
(import '[com.google.gson JsonElement])
(import '[java.util.concurrent CompletableFuture])

(defn- parse-rl
  [s]
  (rl/parse-resource-location s))

(def ^:private gson (gson-util/create-pretty-gson))

(defn- write-json!
  [^CachedOutput cached-output ^PackOutput$PathProvider path-provider ^ResourceLocation id payload]
  (DataProvider/saveStable
   cached-output
  ^JsonElement (.toJsonTree gson (gson-util/normalize-json payload))
   (.json path-provider id)))

(defn create-provider
  [^PackOutput output]
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
        "AcademyCraft Fabric Blockstate Provider"))))
