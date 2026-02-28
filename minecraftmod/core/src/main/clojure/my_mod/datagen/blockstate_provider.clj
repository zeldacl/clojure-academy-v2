(ns my-mod.datagen.blockstate-provider
  "Forge DataGenerator for BlockState JSON files
   
   Automatically generates blockstate JSON from code, eliminating hardcoded
   mod-id issues. Uses gen-class to implement IDataProvider interface."
  (:require [my-mod.config.modid :as modid]
            [clojure.data.json :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator DirectoryCache IDataProvider])
  (:gen-class
   :name my-mod.datagen.BlockStateProvider
   :extends Object
   :implements [net.minecraft.data.IDataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.DirectoryCache] void]
             [getName [] String]]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def BLOCKS_TO_GENERATE
  "List of block names that need blockstate files"
  ["matrix"
   "windgen_main"
   "windgen_pillar"
   "windgen_base"
   "windgen_fan"
   "solar_gen"
   "phase_gen"
   "reso_ore"])

;; ============================================================================
;; Constructor
;; ============================================================================

(defn -init
  "Constructor: Store data generator and file helper in state"
  [generator exfileHelper]
  [[] {:generator generator
       :exfileHelper exfileHelper}])

;; ============================================================================
;; IDataProvider Implementation
;; ============================================================================

(defn -run
  "Main DataGenerator method - generates all blockstates
   
   Args:
     this: BlockStateProvider instance
     cache: DirectoryCache for tracking written files"
  [this ^DirectoryCache cache]
  (let [{:keys [generator exfileHelper]} (.state this)]
    (doseq [block-name BLOCKS_TO_GENERATE]
      (generate-blockstate! generator block-name))))

(defn -getName
  "Get provider name for logging"
  [this]
  "MyMod BlockStates")

;; ============================================================================
;; BlockState Generation
;; ============================================================================

(defn generate-blockstate!
  "Generate single blockstate JSON file
   
   Args:
     generator: DataGenerator instance
     block-name: String block registry name
   
   Output path: assets/{modid}/blockstates/{block-name}.json"
  [^DataGenerator generator block-name]
  (let [blockstate-data
        {:variants
         {"" {:model (str modid/MOD-ID ":" block-name)}}}
        
        output-folder (.getOutputFolder generator)
        blockstates-dir (.. output-folder
                          (resolve "assets")
                          (resolve modid/MOD-ID)
                          (resolve "blockstates"))
        
        file-path (.resolve blockstates-dir (str block-name ".json"))]
    
    ;; Create parent directories
    (java.nio.file.Files/createDirectories blockstates-dir)
    
    ;; Write JSON file
    (spit (.toFile file-path) (json/write-str blockstate-data))
    
    (println (str "Generated blockstate: " (.relativize output-folder file-path)))))
