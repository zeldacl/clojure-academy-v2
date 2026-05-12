(ns cn.li.forge1201.datagen.blockstate-provider
	"Forge BlockState/BlockModel/ItemModel datagen provider.

	Thin platform shell: JSON inference and payload building are shared in mc1201
	blockstate core; this namespace only performs DataProvider IO wiring.")

(require '[cn.li.mcmod.config :as modid])
(require '[cn.li.mcmod.registry.metadata :as registry-metadata])
(require '[cn.li.mc1201.datagen.blockstate-provider-core :as blockstate-core])
(require '[cn.li.mc1201.datagen.resource-location :as rl])
(require '[cn.li.mcmod.block.blockstate-definition :as blockstate-def])
(require '[cn.li.mc1201.datagen.gson-util :as gson-util])
(require '[cn.li.mc1201.datagen.blockstate-support :as bs-support])

(import '[net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target])
(import '[net.minecraft.resources ResourceLocation])
(import '[com.google.gson Gson JsonElement])
(import '[java.util.concurrent CompletableFuture])

(defn- parse-rl
	[s]
	(rl/parse-resource-location s))

(def ^:private ^Gson gson (gson-util/create-pretty-gson))

(defn- write-json!
	[^CachedOutput cached-output ^PackOutput$PathProvider path-provider ^ResourceLocation id payload]
	(DataProvider/saveStable
	 cached-output
	 ^JsonElement (.toJsonTree gson (gson-util/normalize-json payload))
	 (.json path-provider id)))

(defn- write-block-model!
	[^CachedOutput cached-output ^PackOutput$PathProvider path-provider model-name model-json]
	(write-json! cached-output path-provider (parse-rl (str modid/*mod-id* ":" model-name)) model-json))

(defn create
	[^PackOutput output _exfile-helper]
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
																														 :let [model-name (blockstate-core/model-id->model-name model-id)
																																	 model-rl (parse-rl (str modid/*mod-id* ":" model-name))
																																	 model-json (blockstate-core/block-model-json model-name)]]
																												 (write-json! cached-output block-model-path-provider model-rl model-json))
																		item-model-write (when (registry-metadata/should-create-block-item? (name block-key))
																											 (let [item-model-id (blockstate-def/get-item-model-id modid/*mod-id* registry-name)
																														 item-model-name (blockstate-core/model-id->model-name item-model-id)
																														 known-model-ids (set model-ids)
																														 item-block-model-write (when-not (contains? known-model-ids item-model-id)
																																											(write-block-model! cached-output block-model-path-provider item-model-name (blockstate-core/block-model-json item-model-name)))
																														 item-id (parse-rl (str modid/*mod-id* ":" registry-name))
																														 multipart? (blockstate-def/is-multipart-block? definition)]
																												 (concat
																													(when item-block-model-write [item-block-model-write])
																													[(write-json! cached-output item-model-path-provider item-id (blockstate-core/item-model-json registry-name multipart?))])))]
																(concat
																 [(write-json! cached-output blockstate-path-provider block-id blockstate-json)]
																 block-model-writes
																 item-model-write)))
														definitions)]
					(CompletableFuture/allOf
					 (into-array CompletableFuture block-writes))))

			(getName [_this]
				"AcademyCraft Forge Blockstate Provider"))))