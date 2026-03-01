(ns my-mod.forge1201.datagen.blockstate-provider
  (:require [my-mod.config.modid :as modid]
            [my-mod.forge1201.datagen.json-util :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator DirectoryCache IDataProvider])
  (:gen-class
   :name my-mod.forge1201.datagen.BlockStateProvider
   :extends Object
   :implements [net.minecraft.data.IDataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.DirectoryCache] void]
             [getName [] String]]))

(def BLOCKS_TO_GENERATE
  ["matrix" "windgen_main" "windgen_pillar" "windgen_base"
   "windgen_fan" "solar_gen" "phase_gen" "reso_ore"])

(def NODE_BLOCKS_TO_GENERATE
  ["node_standard" "node_basic" "node_advanced"])

(defn -init [generator exfileHelper]
  [[] {:generator generator :exfileHelper exfileHelper}])

(defn -run [this ^DirectoryCache cache]
  (let [{:keys [generator]} (.state this)]
    (doseq [block-name BLOCKS_TO_GENERATE]
      (generate-blockstate! generator block-name))
    (doseq [block-name NODE_BLOCKS_TO_GENERATE]
      (generate-node-blockstate! generator block-name))))

(defn -getName [this]
  "MyMod BlockStates")

(defn generate-blockstate! [^DataGenerator generator block-name]
  (let [blockstate-data {:variants {"normal" {:model (str modid/MOD-ID ":" block-name)}}}
        output-folder (.getOutputFolder generator)
        blockstates-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "blockstates"))
        file-path (.resolve blockstates-dir (str block-name ".json"))]
    (java.nio.file.Files/createDirectories blockstates-dir)
    (spit (.toFile file-path) (json/write-json blockstate-data))
    (println (str "Generated blockstate: " (.relativize output-folder file-path)))))

(defn get-node-blockstate-config [node-type]
  (let [variant-name (.substring node-type 5)
        energy-textures (into {}
                              (for [level (range 5)]
                                [(str level)
                                 {:textures {:side (str modid/MOD-ID ":blocks/node_" variant-name "_side_" level)}}]))
        inventory-config [{:textures {:side (str modid/MOD-ID ":blocks/node_" variant-name "_side_0")
                                      :vert (str modid/MOD-ID ":blocks/node_top_0")}}]]
    {:defaults {:model (str modid/MOD-ID ":node_base")}
     :forge_marker 1
     :variants {:connected {"false" {:textures {:vert (str modid/MOD-ID ":blocks/node_top_0")}}
                            "true"  {:textures {:vert (str modid/MOD-ID ":blocks/node_top_1")}}}
                :energy energy-textures
                :inventory inventory-config}}))

(defn generate-node-blockstate! [^DataGenerator generator block-name]
  (let [blockstate-data (get-node-blockstate-config block-name)
        output-folder (.getOutputFolder generator)
        blockstates-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "blockstates"))
        file-path (.resolve blockstates-dir (str block-name ".json"))]
    (java.nio.file.Files/createDirectories blockstates-dir)
    (spit (.toFile file-path) (json/write-json blockstate-data))
    (println (str "Generated node blockstate: " (.relativize output-folder file-path)))))
