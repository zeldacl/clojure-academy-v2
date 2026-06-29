(ns cn.li.mc1201.datagen.worldgen-provider-core
  "Shared worldgen DataGen provider logic. Builds configured_feature,
  placed_feature, and platform-specific biome_modifier data maps.

  Does NOT do file I/O — returns data so platform providers can write
  via DataProvider/saveStable (Forge) or the Fabric equivalent.")

;; ============================================================================
;; Ore definitions (matching original AcademyCraft worldgen)
;; ============================================================================

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
   :rarity 3   ;; 1-in-3 (~33%) — original AcademyCraft used 30% per chunk;
               ;; rarity_filter uses integer 1/N probability, so 3 is the closest.
   :min-y 5
   :max-y 34}) ;; original: 5 + random.nextInt(30) = [5, 34]

;; ============================================================================
;; JSON data builders (plain Clojure maps)
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
;; File-def builder — returns a seq of {:path [segments...] :data map}
;; Platform providers write these via DataProvider/saveStable.
;; ============================================================================

(defn build-worldgen-file-defs
  "Returns a vector of file definitions for worldgen DataGen.
  Each def is {:path [\"dir\" \"subdir\" \"file.json\"] :data {clojure-map}}.
  The path segments are relative to data/<modid>/ (e.g. [\"worldgen\" \"configured_feature\" \"reso_ore.json\"]).

  Options:
    :gen-ores?          — generate ore features (default true)
    :gen-phase-liquid?  — generate phase liquid pool (default true)
    :platform           — :forge (includes biome_modifier) or :fabric (no biome_modifier)"
  [& {:keys [gen-ores? gen-phase-liquid? platform]
      :or {gen-ores? true gen-phase-liquid? true}}]
  (let [is-forge? (= platform :forge)
        file-defs (atom [])]
    (when gen-ores?
      (doseq [ore ore-defs]
        (let [id (:id ore)]
          (swap! file-defs conj
                 {:path ["worldgen" "configured_feature" (str id ".json")]
                  :data (ore-configured-feature-data ore)}
                 {:path ["worldgen" "placed_feature" (str id ".json")]
                  :data (ore-placed-feature-data ore)})
          (when is-forge?
            (swap! file-defs conj
                   {:path ["forge" "biome_modifier" (str "add_" id ".json")]
                    :data (forge-biome-modifier-data id "underground_ores")})))))
    (when gen-phase-liquid?
      (let [pl phase-liquid-def
            id (:id pl)]
        (swap! file-defs conj
               {:path ["worldgen" "configured_feature" (str id ".json")]
                :data (pool-configured-feature-data pl)}
               {:path ["worldgen" "placed_feature" (str id ".json")]
                :data (pool-placed-feature-data pl)})
        (when is-forge?
          (swap! file-defs conj
                 {:path ["forge" "biome_modifier" (str "add_" id ".json")]
                  :data (forge-biome-modifier-data id "underground_decoration")}))))
    @file-defs))
