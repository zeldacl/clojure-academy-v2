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

(def NODE_BLOCKS_TO_GENERATE
  "List of node block names - have complex blockstate configurations"
  ["node_standard"
   "node_basic"
   "node_advanced"])

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
    ;; Generate simple blockstates (matrix, power generation, etc.)
    (doseq [block-name BLOCKS_TO_GENERATE]
      (generate-blockstate! generator block-name))
    
    ;; Generate node blockstates (with complex energy/connection textures)
    (doseq [block-name NODE_BLOCKS_TO_GENERATE]
      (generate-node-blockstate! generator block-name))))

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
         {"normal" {:model (str modid/MOD-ID ":" block-name)}}}
        
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

;; ============================================================================
;; Node BlockState Generation (with energy/connection variants)
;; ============================================================================

(defn get-node-blockstate-config
  "Get blockstate configuration for a node type
   
   Args:
     node-type: String - 'node_standard', 'node_basic', or 'node_advanced'
   
   Returns:
     Map with blockstate JSON structure"
  [node-type]
  (let [;; Extract the variant name (standard, basic, advanced)
        variant-name (.substring node-type 5)  ; Remove 'node_' prefix
        
        ;; Create side texture names for each energy level
        energy-textures (into {}
          (for [level (range 5)]
            [(str level) 
             {:textures 
              {:side (str modid/MOD-ID ":blocks/node_" variant-name "_side_" level)}}]))
        
        ;; Default inventory representation
        inventory-config
        [{:textures 
          {:side (str modid/MOD-ID ":blocks/node_" variant-name "_side_0")
           :vert (str modid/MOD-ID ":blocks/node_top_0")}}]]
    
    {:defaults {:model (str modid/MOD-ID ":node_base")}
     :forge_marker 1
     :variants
     {:connected
      {"false" {:textures {:vert (str modid/MOD-ID ":blocks/node_top_0")}}
       "true"  {:textures {:vert (str modid/MOD-ID ":blocks/node_top_1")}}}
      :energy energy-textures
      :inventory inventory-config}
     }))

(defn generate-node-blockstate!
  "Generate node blockstate JSON file with energy/connection variants
   
   Args:
     generator: DataGenerator instance
     block-name: String node block registry name
   
   Output path: assets/{modid}/blockstates/{block-name}.json"
  [^DataGenerator generator block-name]
  (let [blockstate-data (get-node-blockstate-config block-name)
        
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
    
    (println (str "Generated node blockstate: " (.relativize output-folder file-path)))))
