(ns cn.li.mc262.datagen.recipe-provider-custom
  "Custom content recipe emission for datagen (26.2 RecipeOutput + ResourceKey)."
  (:require [cn.li.mc262.datagen.metadata-resolver :as metadata-resolver]
            [cn.li.mc262.datagen.resource-location :as rl]
            [cn.li.mcbase.datagen.recipe-core :as recipe-core]
            [cn.li.mcmod.config :as modid])
  (:import [cn.li.mc262.recipe ContentRecipe]
           [cn.li.mc262.shim DelegatingFinishedRecipe]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.core HolderGetter]
           [net.minecraft.data.recipes RecipeOutput]
           [net.minecraft.resources Identifier]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.item.crafting Ingredient]
           [net.minecraft.world.level ItemLike]))

(defn- recipe->content-recipe
  ^ContentRecipe
  ([recipe kind]
   (recipe->content-recipe recipe kind nil))
  ([recipe kind ^HolderGetter items]
   (let [^Ingredient input (metadata-resolver/ingredient-from-spec
                            (:input recipe) rl/parse-resource-location items)
         out (:output recipe)
         ^ItemLike item (metadata-resolver/resolve-item (:item out) rl/parse-resource-location)
         ^ItemStack stack (ItemStack. item (int (:count out 1)))
         consume-liquid (int (or (:consume-liquid recipe) 0))
         craft-time (int (or (:time recipe) 200))
         mode (str (or (:mode recipe) ""))]
     (ContentRecipe. input stack consume-liquid craft-time mode kind))))

(defn- emit-custom-recipe!
  ([^RecipeOutput output recipe kind]
   (emit-custom-recipe! output recipe kind nil))
  ([^RecipeOutput output recipe kind ^HolderGetter items]
   (let [^String mod-id modid/mod-id
         ^String recipe-id (recipe-core/normalize-recipe-id (:id recipe))
         ^Identifier id (ResourceLocations/of mod-id recipe-id)
         ^ContentRecipe content (recipe->content-recipe recipe kind items)]
     (DelegatingFinishedRecipe/accept output id content nil))))

(defn custom-emitters
  "Emitter map for custom recipe types. Optional second arg is HolderGetter<Item>
  for tag ingredients."
  ([^RecipeOutput output]
   (custom-emitters output nil))
  ([^RecipeOutput output ^HolderGetter items]
   {:custom-process (fn [recipe]
                      (emit-custom-recipe! output recipe "process" items))
    :custom-mode (fn [recipe]
                   (emit-custom-recipe! output recipe "mode" items))}))
