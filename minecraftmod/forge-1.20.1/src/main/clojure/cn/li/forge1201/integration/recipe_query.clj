(ns cn.li.forge1201.integration.recipe-query
  "CLIENT-ONLY: Dynamic recipe query for tutorial preview system.
  Replaces original AcademyCraft RecipeHandler.

  Queries Minecraft's RecipeManager for crafting/furnace recipes matching
  a given output item.  Returns data structures usable by
  ac.tutorial.client.preview to build recipe display widgets."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.world.item.crafting RecipeType RecipeManager Recipe Ingredient]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]))

(defn- item-id->stack
  "Resolve an ItemStack from a runtime item-id string."
  [^String item-id]
  (try
    (let [parts (clojure.string/split item-id #":" 2)
          rl (if (= 2 (count parts))
               (ResourceLocation. (first parts) (second parts))
               (ResourceLocation. item-id))
          item (.get BuiltInRegistries/ITEM rl)]
      (when item (ItemStack. item)))
    (catch Exception _ nil)))

(defn- stack->item-id
  "Get runtime item-id string from an ItemStack."
  [^ItemStack stack]
  (when stack
    (try
      (let [item (.getItem stack)
            rl (.getKey BuiltInRegistries/ITEM item)]
        (when rl (str (.getNamespace rl) ":" (.getPath rl))))
      (catch Exception _ nil))))

(defn- recipes-for-type
  "Get all recipes of a given RecipeType whose output matches target-id."
  [^RecipeManager rm ^RecipeType rtype target-id]
  (try
    (let [recipes (.getAllRecipesFor rm rtype)]
      (filter (fn [recipe]
                (= target-id (stack->item-id (.getResultItem recipe nil))))
              recipes))
    (catch Exception _ nil)))

(defn find-recipes
  "Find all recipes that produce `target-id` (e.g. \"my_mod:constrained_ore\").
  Returns a map: {:crafting [...recipes...] :smelting [...recipes...]}"
  [^String target-id]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [level (.level mc)]
        (let [rm (.getRecipeManager level)]
          {:crafting (recipes-for-type rm RecipeType/CRAFTING target-id)
           :smelting (recipes-for-type rm RecipeType/SMELTING target-id)})))
    (catch Exception e
      (log/debug "Recipe query failed for" target-id ":" (.getMessage e))
      nil)))

(defn has-recipes?
  "Quick check: does `target-id` have any recipes?"
  [^String target-id]
  (when-let [result (find-recipes target-id)]
    (or (seq (:crafting result)) (seq (:smelting result)))))

(defn first-recipe-for
  "Get the first recipe of a given kind for `target-id`.
  Returns {:input [item-id...] :output item-id :count N} or nil."
  [^String target-id recipe-kind]
  (when-let [result (find-recipes target-id)]
    (when-let [recipes (get result (case recipe-kind
                                     :smelting :smelting
                                     (:crafting :imag-fusor :metal-former) :crafting
                                     nil))]
      (when-let [^Recipe recipe (first (seq recipes))]
        (try
          (let [^ItemStack output (.getResultItem recipe nil)]
            {:input (mapv (fn [^Ingredient ing]
                            (when-let [stacks (.getItems ing)]
                              (when-let [^ItemStack s (first (seq stacks))]
                                (stack->item-id s))))
                          (.getIngredients recipe))
             :output (stack->item-id output)
             :count (.getCount output)})
          (catch Exception _ nil))))))
