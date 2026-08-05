(ns cn.li.mc262.datagen.recipe-provider-custom
  "Custom content recipe emission for datagen (26.2 RecipeOutput + ResourceKey).

  ItemStack.CODEC encodes via Item.CODEC_WITH_BOUND_COMPONENTS, so the stack's
  Holder must be a registered Reference with components bound. During datagen
  some item holders are still unbound — bind EMPTY components onto the lookup
  Reference before constructing the stack."
  (:require [cn.li.mc262.datagen.metadata-resolver :as metadata-resolver]
            [cn.li.mc262.datagen.resource-location :as rl]
            [cn.li.mcbase.datagen.recipe-core :as recipe-core]
            [cn.li.mcmod.config :as modid])
  (:import [cn.li.mc262.recipe ContentRecipe]
           [cn.li.mc262.shim DelegatingFinishedRecipe]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.core Holder$Reference]
           [net.minecraft.core HolderGetter]
           [net.minecraft.core.component DataComponentMap]
           [net.minecraft.core.registries Registries]
           [net.minecraft.data.recipes RecipeOutput]
           [net.minecraft.resources Identifier ResourceKey]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.item.crafting Ingredient]))

(defn- bound-item-holder
  "Return a registry Holder with components bound for ItemStack.CODEC."
  ^Holder$Reference
  [item-id ^HolderGetter items]
  (let [^Identifier id (rl/parse-resource-location item-id)
        ^ResourceKey key (ResourceKey/create Registries/ITEM id)
        holder (.getOrThrow items key)]
    (when-not (instance? Holder$Reference holder)
      (throw (ex-info "Expected Holder.Reference for recipe item"
                      {:item-id item-id :holder holder})))
    (let [^Holder$Reference ref holder]
      (when-not (.areComponentsBound ref)
        (.bindComponents ref DataComponentMap/EMPTY))
      ref)))

(defn- item-stack
  ^ItemStack
  [item-id count ^HolderGetter items]
  (ItemStack. (bound-item-holder item-id items) (int count)))

(defn- recipe->content-recipe
  ^ContentRecipe
  [recipe kind ^HolderGetter items]
  (let [^Ingredient input (metadata-resolver/ingredient-from-spec
                           (:input recipe) rl/parse-resource-location items)
        out (:output recipe)
        ^ItemStack stack (item-stack (:item out) (:count out 1) items)
        consume-liquid (int (or (:consume-liquid recipe) 0))
        craft-time (int (or (:time recipe) 200))
        mode (str (or (:mode recipe) ""))]
    (ContentRecipe. input stack consume-liquid craft-time mode kind)))

(defn- emit-custom-recipe!
  [^RecipeOutput output recipe kind ^HolderGetter items]
  (let [^String mod-id modid/mod-id
        ^String recipe-id (recipe-core/normalize-recipe-id (:id recipe))
        ^Identifier id (ResourceLocations/of mod-id recipe-id)
        ^ContentRecipe content (recipe->content-recipe recipe kind items)]
    (DelegatingFinishedRecipe/accept output id content nil)))

(defn custom-emitters
  "Emitter map for custom recipe types. HolderGetter<Item> is required."
  ([^RecipeOutput output]
   (custom-emitters output nil))
  ([^RecipeOutput output ^HolderGetter items]
   (when (nil? items)
     (throw (ex-info "custom-emitters requires HolderGetter<Item> on 26.2" {})))
   {:custom-process (fn [recipe]
                      (emit-custom-recipe! output recipe "process" items))
    :custom-mode (fn [recipe]
                   (emit-custom-recipe! output recipe "mode" items))}))
