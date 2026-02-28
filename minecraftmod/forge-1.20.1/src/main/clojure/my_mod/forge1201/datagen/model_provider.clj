(ns my-mod.forge1201.datagen.model-provider
  (:require [my-mod.config.modid :as modid]
            [clojure.data.json :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator DirectoryCache IDataProvider])
  (:gen-class
   :name my-mod.forge1201.datagen.ModelProvider
   :extends Object
   :implements [net.minecraft.data.IDataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.DirectoryCache] void]
             [getName [] String]]))

(def BLOCK_MODELS
  {"matrix" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/matrix")}}
   "windgen_main" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/windgen_main")}}
   "windgen_pillar" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/windgen_pillar")}}
   "windgen_base" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/windgen_base")}}
   "windgen_fan" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/windgen_fan")}}
   "solar_gen" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/solar_gen")}}
   "phase_gen" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/phase_gen")}}
   "reso_ore" {:parent "block/cube_all" :textures {:all (str modid/MOD-ID ":blocks/reso_ore")}}
   "node_base" {:parent "block/cube"
                :textures {:down "#vert" :up "#vert" :north "#side" :south "#side" :east "#side" :west "#side" :particle "#side"}}})

(defn -init [generator exfileHelper]
  [[] {:generator generator :exfileHelper exfileHelper}])

(defn -run [this ^DirectoryCache cache]
  (let [{:keys [generator]} (.state this)]
    (doseq [[block-name model-spec] BLOCK_MODELS]
      (generate-block-model! generator block-name model-spec))))

(defn -getName [this]
  "MyMod Block Models")

(defn generate-block-model! [^DataGenerator generator block-name model-spec]
  (let [model-data (-> model-spec
                       (update-keys (fn [k] (case k :parent "parent" :textures "textures" (name k))))
                       (update :textures (fn [textures]
                                           (reduce (fn [m [k v]] (assoc m (name k) v)) {} textures))))
        output-folder (.getOutputFolder generator)
        models-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "models") (resolve "block"))
        file-path (.resolve models-dir (str block-name ".json"))]
    (java.nio.file.Files/createDirectories models-dir)
    (spit (.toFile file-path) (json/write-str model-data))
    (println (str "Generated model: " (.relativize output-folder file-path)))))
