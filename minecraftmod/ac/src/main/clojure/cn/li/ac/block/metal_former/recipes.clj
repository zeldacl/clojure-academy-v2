(ns cn.li.ac.block.metal-former.recipes
  "Metal Former recipe system (AcademyCraft parity port)."
  (:require [clojure.string :as str]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.platform.item :as pitem]))

(def mode-order
  [:plate :incise :etch :refine])

(defn normalize-mode
  [mode]
  (let [s (-> (cond
                (keyword? mode) (name mode)
                (string? mode) mode
                :else "plate")
              str/lower-case)]
    (case s
      "plate" :plate
      "incise" :incise
      "etch" :etch
      "refine" :refine
      :plate)))

(defn mode->string
  [mode]
  (name (normalize-mode mode)))

(defn mode->icon-texture
  [mode]
  (str "academy:textures/guis/icons/icon_former_" (mode->string mode) ".png"))

(defn item-id-from-stack
  "Best-effort item id extraction from ItemStack."
  [stack]
  (when stack
    (let [item-obj (try (pitem/item-get-item stack) (catch Exception _ nil))
          reg-id (when item-obj
                   (try (pitem/item-get-registry-name item-obj)
                        (catch Exception _ nil)))
          desc-id (when item-obj
                    (try (pitem/item-get-description-id item-obj)
                         (catch Exception _ nil)))]
      (or reg-id
          (when (string? desc-id)
            (let [tail (last (str/split desc-id #"\\."))]
              (when (and (string? tail) (not (str/blank? tail)))
                (str modid/MOD-ID ":" tail))))))))

(defn- stack-empty?
  [stack]
  (or (nil? stack)
      (try (boolean (pitem/item-is-empty? stack))
           (catch Exception _ false))))

(defn- stack-count
  [stack]
  (if (stack-empty? stack)
    0
    (try (int (pitem/item-get-count stack))
         (catch Exception _ 0))))

(defn- normalize-item-id
  [item-id]
  (when (string? item-id)
    (if (str/includes? item-id ":")
      item-id
      (str modid/MOD-ID ":" item-id))))

(defn- stack-matches-item?
  [stack item-spec]
  (and (not (stack-empty? stack))
       item-spec
       (let [expected-id (normalize-item-id (:item item-spec))
             actual-id (item-id-from-stack stack)
             min-count (int (or (:count item-spec) 1))]
         (and expected-id
              actual-id
              (= expected-id actual-id)
              (>= (stack-count stack) min-count)))))

(defn- recipe-output-stack
  [recipe]
  (let [output (:output recipe)
        item-id (normalize-item-id (:item output))
        count (int (or (:count output) 1))]
    (when (and item-id (pos? count))
      (pitem/create-item-stack-by-id item-id count))))

(defn- default-recipes
  []
  (let [m modid/MOD-ID]
    [;; Restored AcademyCraft material chain.
     {:id "mf_incise_imag_silicon_ingot"
      :mode "incise"
      :input {:item (str m ":imag_silicon_ingot") :count 1}
      :output {:item (str m ":wafer") :count 2}}
     {:id "mf_incise_wafer"
      :mode "incise"
      :input {:item (str m ":wafer") :count 1}
      :output {:item (str m ":imag_silicon_piece") :count 4}}
     {:id "mf_incise_reinforced_iron_plate"
      :mode "incise"
      :input {:item (str m ":reinforced_iron_plate") :count 1}
      :output {:item (str m ":needle") :count 6}}
     {:id "mf_etch_data_chip"
      :mode "etch"
      :input {:item (str m ":data_chip") :count 1}
      :output {:item (str m ":calc_chip") :count 1}}
     {:id "mf_etch_wafer"
      :mode "etch"
      :input {:item (str m ":wafer") :count 1}
      :output {:item (str m ":silbarn") :count 1}}
     {:id "mf_plate_iron"
      :mode "plate"
      :input {:item "minecraft:iron_ingot" :count 1}
      :output {:item (str m ":reinforced_iron_plate") :count 1}}
     {:id "mf_plate_constraint"
      :mode "plate"
      :input {:item (str m ":constraint_ingot") :count 1}
      :output {:item (str m ":constraint_plate") :count 1}}

     {:id "mf_refine_imaginary_ore"
      :mode "refine"
      :input {:item (str m ":imaginary_ore") :count 1}
      :output {:item (str m ":imag_silicon_ingot") :count 4}}
     {:id "mf_refine_constrained_ore"
      :mode "refine"
      :input {:item (str m ":constrained_ore") :count 1}
      :output {:item (str m ":constraint_ingot") :count 2}}
     {:id "mf_refine_reso_ore"
      :mode "refine"
      :input {:item (str m ":reso_ore") :count 1}
      :output {:item (str m ":reso_crystal") :count 3}}
     {:id "mf_refine_crystal_ore"
      :mode "refine"
      :input {:item (str m ":crystal_ore") :count 1}
      :output {:item (str m ":crystal_low") :count 4}}

     ;; 1.12 OreDictionary refine presets mapped to vanilla/tag-era equivalents.
     {:id "mf_refine_gold_ore"
      :mode "refine"
      :input {:item "minecraft:gold_ore" :count 1}
      :output {:item "minecraft:gold_ingot" :count 2}}
     {:id "mf_refine_deepslate_gold_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_gold_ore" :count 1}
      :output {:item "minecraft:gold_ingot" :count 2}}
     {:id "mf_refine_iron_ore"
      :mode "refine"
      :input {:item "minecraft:iron_ore" :count 1}
      :output {:item "minecraft:iron_ingot" :count 2}}
     {:id "mf_refine_deepslate_iron_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_iron_ore" :count 1}
      :output {:item "minecraft:iron_ingot" :count 2}}
     {:id "mf_refine_emerald_ore"
      :mode "refine"
      :input {:item "minecraft:emerald_ore" :count 1}
      :output {:item "minecraft:emerald" :count 2}}
     {:id "mf_refine_deepslate_emerald_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_emerald_ore" :count 1}
      :output {:item "minecraft:emerald" :count 2}}
     {:id "mf_refine_quartz_ore"
      :mode "refine"
      :input {:item "minecraft:nether_quartz_ore" :count 1}
      :output {:item "minecraft:quartz" :count 2}}
     {:id "mf_refine_diamond_ore"
      :mode "refine"
      :input {:item "minecraft:diamond_ore" :count 1}
      :output {:item "minecraft:diamond" :count 2}}
     {:id "mf_refine_deepslate_diamond_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_diamond_ore" :count 1}
      :output {:item "minecraft:diamond" :count 2}}
     {:id "mf_refine_redstone_ore"
      :mode "refine"
      :input {:item "minecraft:redstone_ore" :count 1}
      :output {:item "minecraft:redstone_block" :count 1}}
     {:id "mf_refine_deepslate_redstone_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_redstone_ore" :count 1}
      :output {:item "minecraft:redstone_block" :count 1}}
     {:id "mf_refine_lapis_ore"
      :mode "refine"
      :input {:item "minecraft:lapis_ore" :count 1}
      :output {:item "minecraft:lapis_lazuli" :count 12}}
     {:id "mf_refine_deepslate_lapis_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_lapis_ore" :count 1}
      :output {:item "minecraft:lapis_lazuli" :count 12}}
     {:id "mf_refine_coal_ore"
      :mode "refine"
      :input {:item "minecraft:coal_ore" :count 1}
      :output {:item "minecraft:coal" :count 2}}
     {:id "mf_refine_deepslate_coal_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_coal_ore" :count 1}
      :output {:item "minecraft:coal" :count 2}}
     {:id "mf_refine_copper_ore"
      :mode "refine"
      :input {:item "minecraft:copper_ore" :count 1}
      :output {:item "minecraft:copper_ingot" :count 2}}
     {:id "mf_refine_deepslate_copper_ore"
      :mode "refine"
      :input {:item "minecraft:deepslate_copper_ore" :count 1}
      :output {:item "minecraft:copper_ingot" :count 2}}]))

(defonce recipes
  (atom (vec (default-recipes))))

(defn get-recipe-by-id
  [recipe-id]
  (first (filter #(= (:id %) recipe-id) @recipes)))

(defn find-recipe
  "Find recipe by input stack and machine mode."
  [input-item mode]
  (let [mk (normalize-mode mode)]
    (first
      (filter
        (fn [recipe]
          (and (= mk (normalize-mode (:mode recipe)))
               (stack-matches-item? input-item (:input recipe))))
        @recipes))))

(defn can-accept-input?
  [recipe input-item mode]
  (and recipe
       (= (normalize-mode (:mode recipe)) (normalize-mode mode))
       (stack-matches-item? input-item (:input recipe))))

(defn can-output?
  [recipe output-slot-item]
  (let [result-stack (recipe-output-stack recipe)]
    (cond
      (nil? result-stack) false
      (stack-empty? output-slot-item) true
      :else
      (and (try (pitem/item-is-equal? output-slot-item result-stack)
                (catch Exception _ false))
           (<= (+ (stack-count output-slot-item) (stack-count result-stack))
               (int (or (try (pitem/item-get-max-stack-size output-slot-item)
                             (catch Exception _ 64))
                        64)))))))

(defn build-output-stack
  [recipe]
  (recipe-output-stack recipe))

(defn can-form?
  "True when this exact recipe can continue crafting in current mode/state."
  [recipe input-item output-item mode]
  (and (can-accept-input? recipe input-item mode)
       (can-output? recipe output-item)))
