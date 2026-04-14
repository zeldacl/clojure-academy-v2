(ns cn.li.ac.block.imag-fusor.recipes
  "Imaginary Fusor recipe system (ported from AcademyCraft MFIF recipes)."
  (:require [clojure.string :as str]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.imag-fusor.config :as cfg]
            [cn.li.mcmod.platform.item :as pitem]))

(defn item-id-from-stack
  "Best-effort item id extraction from an ItemStack.
   Returns namespaced id string when available, else nil."
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

(defn- stack-count
  [stack]
  (if stack
    (try (int (pitem/item-get-count stack)) (catch Exception _ 0))
    0))

(defn- normalize-item-id
  [item-id]
  (when (string? item-id)
    (if (str/includes? item-id ":")
      item-id
      (str modid/MOD-ID ":" item-id))))

(defn- stack-matches-item?
  [stack item-spec]
  (and stack
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

(defonce recipes
  (atom
    [{:id "if_crystal_low_to_normal"
      :input {:item (str modid/MOD-ID ":crystal_low") :count 1}
      :output {:item (str modid/MOD-ID ":crystal_normal") :count 1}
      :consume-liquid 3000
      :time cfg/craft-time-ticks}
     {:id "if_crystal_normal_to_pure"
      :input {:item (str modid/MOD-ID ":crystal_normal") :count 1}
      :output {:item (str modid/MOD-ID ":crystal_pure") :count 1}
      :consume-liquid 8000
      :time cfg/craft-time-ticks}]))

(defn find-recipe
  "Find a recipe matching the crystal input stack."
  [input-item]
  (first (filter #(stack-matches-item? input-item (:input %)) @recipes)))

(defn get-recipe-by-id
  "Get a recipe by its ID"
  [recipe-id]
  (first (filter #(= (:id %) recipe-id) @recipes)))

(defn can-output?
  "Check if recipe output can be inserted into current output slot stack."
  [recipe output-slot-item]
  (let [result-stack (recipe-output-stack recipe)]
    (cond
      (nil? result-stack) false
      (nil? output-slot-item) true
      :else
      (and (try (pitem/item-is-equal? output-slot-item result-stack) (catch Exception _ false))
           (<= (+ (stack-count output-slot-item) (stack-count result-stack))
               (int (or (try (pitem/item-get-max-stack-size output-slot-item)
                             (catch Exception _ 64))
                        64)))))))

(defn build-output-stack
  "Build the output stack for a completed recipe."
  [recipe]
  (recipe-output-stack recipe))

(defn can-craft?
  "Check whether crafting can proceed this tick.
   Mirrors original checks: item still valid, enough energy+liquid, output accepts result."
  [recipe input-item output-item energy liquid-amount]
  (and recipe
       (stack-matches-item? input-item (:input recipe))
       (>= (double energy) cfg/energy-per-tick)
       (>= (int liquid-amount) (int (:consume-liquid recipe 0)))
       (can-output? recipe output-item)))
