(ns cn.li.fabric1201.datagen.blockstate-provider
  "Fabric BlockState/BlockModel/ItemModel datagen provider.

  Generates JSON from shared hook-driven blockstate definitions and registry metadata.")

(require '[cn.li.mcmod.config :as modid])
(require '[cn.li.mcmod.registry.metadata :as registry-metadata])
(require '[cn.li.mc1201.datagen.resource-location :as rl])
(require '[cn.li.mcmod.block.blockstate-definition :as blockstate-def])
(require '[cn.li.mc1201.datagen.gson-util :as gson-util])
(require '[cn.li.mc1201.datagen.blockstate-support :as bs-support])

(import '[net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target])
(import '[net.minecraft.resources ResourceLocation])
(import '[com.google.gson JsonElement])
(import '[java.util.concurrent CompletableFuture])

(defn- parse-rl
  [s]
  (rl/parse-resource-location s))

(def ^:private gson (gson-util/create-pretty-gson))

(defn- item-texture-from-spec
  [registry-name]
  (let [block-spec (registry-metadata/get-block-spec (keyword registry-name))]
    (or (get-in block-spec [:rendering :item-texture])
        (get-in block-spec [:rendering :texture])
        (str modid/*mod-id* ":block/" registry-name))))

(defn- block-texture-from-spec
  [registry-name]
  (let [block-spec (registry-metadata/get-block-spec (keyword registry-name))]
    (or (get-in block-spec [:rendering :texture])
        (str modid/*mod-id* ":block/" registry-name))))

(defn- block-parent-from-spec
  [registry-name]
  (let [block-spec (registry-metadata/get-block-spec (keyword registry-name))]
    (or (get-in block-spec [:rendering :parent])
        "minecraft:block/cube_all")))

(defn- model-id->registry-name
  [model-name]
  (let [from-node (when (try
                          (requiring-resolve 'cn.li.ac.block.wireless-node.blockstate/get-node-model-texture-config)
                          true
                          (catch Throwable _ false))
                    (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.blockstate/get-node-model-texture-config)]
                      (when (f model-name)
                        (second (re-find #"^(node_[^_]+)" model-name)))))]
    (or from-node
        model-name)))

(defn- block-model-json
  [model-name]
  (if-let [cube-config (blockstate-def/get-model-cube-texture-config model-name)]
    (bs-support/cube-model-json cube-config)
    (if-let [texture-config (blockstate-def/get-model-texture-config model-name)]
      (bs-support/cube-model-json {:down (:vert texture-config)
                                   :up (:vert texture-config)
                                   :north (:side texture-config)
                                   :south (:side texture-config)
                                   :east (:side texture-config)
                                   :west (:side texture-config)})
      (let [registry-name (model-id->registry-name model-name)
            block-spec (registry-metadata/get-block-spec (keyword registry-name))
            parent (block-parent-from-spec registry-name)
            explicit-texture (or (get-in block-spec [:rendering :item-texture])
                                 (get-in block-spec [:rendering :texture]))]
        (if (or explicit-texture (not= parent "minecraft:block/cube_all"))
          (bs-support/parent-model-json
           parent
           (when explicit-texture {:all explicit-texture}))
          (bs-support/cube-all-model-json (block-texture-from-spec registry-name)))))))

(defn- write-json!
  [^CachedOutput cached-output ^PackOutput$PathProvider path-provider ^ResourceLocation id payload]
  (DataProvider/saveStable
   cached-output
  ^JsonElement (.toJsonTree gson (gson-util/normalize-json payload))
   (.json path-provider id)))

(defn- write-block-model!
  [^CachedOutput cached-output ^PackOutput$PathProvider path-provider model-name model-json]
  (write-json! cached-output path-provider (parse-rl (str modid/*mod-id* ":" model-name)) model-json))

(defn- item-model-json
  [registry-name]
  (let [block-spec (registry-metadata/get-block-spec (keyword registry-name))
        flat-item? (true? (get-in block-spec [:rendering :flat-item-icon?]))
        item-model-id (blockstate-def/get-item-model-id modid/*mod-id* registry-name)]
    (if flat-item?
      (bs-support/flat-item-model-json (item-texture-from-spec registry-name))
      (bs-support/parent-model-json item-model-id))))

(defn create-provider
  [^PackOutput output]
  (let [blockstate-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "blockstates")
        block-model-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/block")
        item-model-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/item")]
    (reify DataProvider
      (^CompletableFuture run [_this ^CachedOutput cached-output]
        (let [definitions (blockstate-def/get-all-definitions)
              block-writes (mapcat
                            (fn [[block-key definition]]
                              (let [registry-name (:registry-name definition)
                                    block-id (parse-rl (str modid/*mod-id* ":" registry-name))
                                    first-model (some-> definition :parts first :models first)
                                    blockstate-json (if (blockstate-def/is-multipart-block? definition)
                                                      (bs-support/multipart-blockstate-json (:parts definition))
                                                      (bs-support/simple-blockstate-json first-model))
                                    model-ids (->> (:parts definition)
                                                   (mapcat :models)
                                                   distinct)
                                    block-model-writes (for [model-id model-ids
                                                             :let [model-name (bs-support/model-id->model-name model-id)
                                                                   model-rl (parse-rl (str modid/*mod-id* ":" model-name))
                                                                   model-json (block-model-json model-name)]]
                                                         (write-json! cached-output block-model-path-provider model-rl model-json))
                                    item-model-write (when (registry-metadata/should-create-block-item? (name block-key))
                                                       (let [item-model-id (blockstate-def/get-item-model-id modid/*mod-id* registry-name)
                                                             item-model-name (bs-support/model-id->model-name item-model-id)
                                                             known-model-ids (set model-ids)
                                                             item-block-model-write (when-not (contains? known-model-ids item-model-id)
                                                                                      (write-block-model! cached-output block-model-path-provider item-model-name (block-model-json item-model-name)))
                                                             item-id (parse-rl (str modid/*mod-id* ":" registry-name))]
                                                         (concat
                                                           (when item-block-model-write [item-block-model-write])
                                                           [(write-json! cached-output item-model-path-provider item-id (item-model-json registry-name))])))]
                                (concat
                                 [(write-json! cached-output blockstate-path-provider block-id blockstate-json)]
                                 block-model-writes
                                 item-model-write)))
                            definitions)]
          (CompletableFuture/allOf
           (into-array CompletableFuture block-writes))))

      (getName [_this]
        "AcademyCraft Fabric Blockstate Provider"))))
