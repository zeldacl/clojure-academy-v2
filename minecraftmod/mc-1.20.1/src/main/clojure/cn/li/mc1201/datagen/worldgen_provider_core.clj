(ns cn.li.mc1201.datagen.worldgen-provider-core
  "Shared worldgen DataGen provider logic. Generates configured_feature,
  placed_feature, and platform-specific biome_modifier JSONs.

  Uses com.google.gson (bundled with Minecraft) for JSON serialization."
  (:require [clojure.java.io :as io]
            [cn.li.mcmod.util.log :as log])
  (:import [com.google.gson GsonBuilder Gson]
           [java.io FileWriter]
           [java.util.concurrent CompletableFuture]))

;; ============================================================================
;; Ore definitions (matching original AcademyCraft worldgen)
;; ============================================================================

(def ^:private gson
  (.. (GsonBuilder.) (setPrettyPrinting) (disableHtmlEscaping) (create)))

(def ^:private ore-defs
  [{:id "constrained_ore" :name "Constrained Ore" :size 12 :count 8}
   {:id "reso_ore" :name "Resonance Ore" :size 9 :count 8}
   {:id "crystal_ore" :name "Crystal Ore" :size 12 :count 12}
   {:id "imaginary_ore" :name "Imaginary Silicon Ore" :size 11 :count 8}])

;; ============================================================================
;; Phase liquid pool
;; ============================================================================

(def ^:private phase-liquid-def
  {:id "phase_liquid"
   :name "Phase Liquid Pool"
   :rarity 3   ;; ~30% chance per chunk
   :min-y 5
   :max-y 35})

;; ============================================================================
;; JSON data builders (plain Clojure maps → Gson)
;; ============================================================================

(defn- ore-configured-feature-data
  [{:keys [id size]}]
  {"type" "minecraft:ore"
   "config" {"size" size
             "discard_chance_on_air_exposure" 0.0
             "targets" [{"target" {"predicate_type" "minecraft:tag_match"
                                   "tag" "minecraft:stone_ore_replaceables"}
                         "state" {"Name" (str "my_mod:" id)}}
                        {"target" {"predicate_type" "minecraft:tag_match"
                                   "tag" "minecraft:deepslate_ore_replaceables"}
                         "state" {"Name" (str "my_mod:" id)}}]}})

(defn- ore-placed-feature-data
  [{:keys [id count]}]
  {"feature" (str "my_mod:" id)
   "placement" [{"type" "minecraft:count" "count" count}
                {"type" "minecraft:in_square"}
                {"type" "minecraft:height_range"
                 "height" {"type" "minecraft:uniform"
                           "min_inclusive" {"absolute" -64}
                           "max_inclusive" {"absolute" 60}}}
                {"type" "minecraft:biome"}]})

(defn- pool-configured-feature-data
  [_def]
  {"type" "my_mod:configurable_pool"
   "config" {}})

(defn- pool-placed-feature-data
  [{:keys [id rarity min-y max-y]}]
  {"feature" (str "my_mod:" id)
   "placement" [{"type" "minecraft:rarity_filter" "chance" rarity}
                {"type" "minecraft:in_square"}
                {"type" "minecraft:height_range"
                 "height" {"type" "minecraft:uniform"
                           "min_inclusive" {"absolute" min-y}
                           "max_inclusive" {"absolute" max-y}}}
                {"type" "minecraft:biome"}]})

(defn- forge-biome-modifier-data
  [feature-id step]
  {"type" "forge:add_features"
   "biomes" "#minecraft:is_overworld"
   "features" (str "my_mod:" feature-id)
   "step" step})

;; ============================================================================
;; File writing
;; ============================================================================

(defn- write-json!
  [output-dir file-name data]
  (let [f (io/file (str output-dir) file-name)]
    (.mkdirs (.getParentFile f))
    (with-open [writer (FileWriter. f)]
      (.toJson ^Gson gson data writer))))

(defn- dir-for-path
  [output-dir & segments]
  (apply io/file output-dir "data" "my_mod" segments))

(defn generate-forge-worldgen!
  "Generate all worldgen JSON files for Forge platform."
  [output-dir config]
  (let [gen-ores? (boolean (:gen-ores? config true))
        gen-phase? (boolean (:gen-phase-liquid? config true))
        cf-dir (dir-for-path output-dir "worldgen" "configured_feature")
        pf-dir (dir-for-path output-dir "worldgen" "placed_feature")
        bm-dir (dir-for-path output-dir "forge" "biome_modifier")]
    (when gen-ores?
      (doseq [ore ore-defs]
        (let [id (:id ore)]
          (write-json! cf-dir (str id ".json") (ore-configured-feature-data ore))
          (write-json! pf-dir (str id ".json") (ore-placed-feature-data ore))
          (write-json! bm-dir (str "add_" id ".json")
                       (forge-biome-modifier-data id "underground_ores")))))
    (when gen-phase?
      (let [pl phase-liquid-def
            id (:id pl)]
        (write-json! cf-dir (str id ".json") (pool-configured-feature-data pl))
        (write-json! pf-dir (str id ".json") (pool-placed-feature-data pl))
        (write-json! bm-dir (str "add_" id ".json")
                     (forge-biome-modifier-data id "underground_decoration"))))
    (log/info "Generated Forge worldgen DataGen files"
              {:ores gen-ores? :phase-liquid gen-phase?
               :ore-count (if gen-ores? (count ore-defs) 0)})))

(defn generate-fabric-worldgen!
  "Generate worldgen JSON files for Fabric platform.
  Fabric uses different biome modification (via Fabric API datagen)
  but the configured_feature and placed_feature JSONs are the same."
  [output-dir config]
  (let [gen-ores? (boolean (:gen-ores? config true))
        gen-phase? (boolean (:gen-phase-liquid? config true))
        cf-dir (dir-for-path output-dir "worldgen" "configured_feature")
        pf-dir (dir-for-path output-dir "worldgen" "placed_feature")]
    (when gen-ores?
      (doseq [ore ore-defs]
        (let [id (:id ore)]
          (write-json! cf-dir (str id ".json") (ore-configured-feature-data ore))
          (write-json! pf-dir (str id ".json") (ore-placed-feature-data ore)))))
    (when gen-phase?
      (let [pl phase-liquid-def]
        (write-json! cf-dir (str (:id pl) ".json") (pool-configured-feature-data pl))
        (write-json! pf-dir (str (:id pl) ".json") (pool-placed-feature-data pl))))
    (log/info "Generated Fabric worldgen DataGen files"
              {:ores gen-ores? :phase-liquid gen-phase?
               :ore-count (if gen-ores? (count ore-defs) 0)})))
