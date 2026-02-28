(ns my-mod.forge1201.datagen.item-model-provider
  (:require [my-mod.config.modid :as modid]
            [clojure.data.json :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator DirectoryCache IDataProvider])
  (:gen-class
   :name my-mod.forge1201.datagen.ItemModelProvider
   :extends Object
   :implements [net.minecraft.data.IDataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.DirectoryCache] void]
             [getName [] String]]))

(def ITEM_MODELS
  {"wafer" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/wafer")}}
   "tutorial" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/tutorial")}}
   "terminal_installer" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/terminal_installer")}}
   "silbarn" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/silbarn")}}
   "reso_crystal" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/reso_crystal")}}
   "resonance_component" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/resonance_component")}}
   "reinforced_iron_plate" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/reinforced_iron_plate")}}
   "needle" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/needle")}}
   "mat_core_0" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/mat_core_0")}}
   "mat_core_1" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/mat_core_1")}}
   "mat_core_2" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/mat_core_2")}}
   "media_0" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/media_sisters_noise")}}
   "media_1" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/media_only_my_railgun")}}
   "media_2" {:parent "item/generated" :textures {:layer0 (str modid/MOD-ID ":items/media_level5_judgelight")}}})

(defn -init [generator exfileHelper]
  [[] {:generator generator :exfileHelper exfileHelper}])

(defn -run [this ^DirectoryCache cache]
  (let [{:keys [generator]} (.state this)]
    (doseq [[item-name model-spec] ITEM_MODELS]
      (generate-item-model! generator item-name model-spec))))

(defn -getName [this]
  "MyMod Item Models")

(defn generate-item-model! [^DataGenerator generator item-name model-spec]
  (let [model-data (-> model-spec
                       (update-keys (fn [k] (case k :parent "parent" :textures "textures" (name k))))
                       (update :textures (fn [textures]
                                           (reduce (fn [m [k v]] (assoc m (name k) v)) {} textures))))
        output-folder (.getOutputFolder generator)
        models-dir (.. output-folder (resolve "assets") (resolve modid/MOD-ID) (resolve "models") (resolve "item"))
        file-path (.resolve models-dir (str item-name ".json"))]
    (java.nio.file.Files/createDirectories models-dir)
    (spit (.toFile file-path) (json/write-str model-data))
    (println (str "Generated item model: " (.relativize output-folder file-path)))))
