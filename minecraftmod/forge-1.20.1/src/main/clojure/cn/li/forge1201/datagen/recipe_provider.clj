(ns cn.li.forge1201.datagen.recipe-provider
  "Forge 1.20.1 recipe datagen provider."
  (:require [clojure.string :as str]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.metadata-resolver :as metadata-resolver]
            [cn.li.mc1201.datagen.recipe-patterns :as recipe-patterns]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
  (:import [java.util.function Consumer]
           [net.minecraft.advancements CriterionTriggerInstance]
           [net.minecraft.advancements.critereon InventoryChangeTrigger$TriggerInstance]
           [net.minecraft.data PackOutput]
           [net.minecraft.data.recipes RecipeCategory RecipeProvider
            ShapedRecipeBuilder ShapelessRecipeBuilder SimpleCookingRecipeBuilder]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item.crafting Ingredient]
           [net.minecraft.world.level ItemLike]
           [net.minecraftforge.common.data ExistingFileHelper]))

(defn- load-recipes
  []
  (vec (datagen-metadata/get-recipes)))

(defn- define-key!
  [^ShapedRecipeBuilder builder k spec]
  (let [ch (if (char? k) k (first (str k)))
        ^Character key-char (Character/valueOf (char ch))
        ingredient (metadata-resolver/ingredient-from-spec spec rl/parse-resource-location)]
    (.define builder key-char ingredient)))

(defn- criterion-for-item
  "Create Forge criterion trigger for item unlock."
  ^CriterionTriggerInstance
  [item-id]
  (let [^ItemLike item (metadata-resolver/resolve-item item-id rl/parse-resource-location)]
    (InventoryChangeTrigger$TriggerInstance/hasItems
      ^"[Lnet.minecraft.world.level.ItemLike;"
      (into-array ItemLike [item]))))

(defn- add-unlock-to-builder!
  "Apply criterion unlock to recipe builder if unlock item found."
  [^Object builder recipe]
  (when-let [unlock-item-id (recipe-patterns/first-item-id recipe)]
    (let [{:keys [unlock-name criterion-instance]}
          (recipe-patterns/criterion-metadata unlock-item-id criterion-for-item)]
      (.unlockedBy builder ^String unlock-name ^CriterionTriggerInstance criterion-instance)))
  builder)

(defn- emit-shaped!
  [^Consumer writer recipe]
  (let [metadata (recipe-patterns/shaped-recipe-metadata recipe)
        result-item (metadata-resolver/resolve-item (get-in metadata [:result :item]) rl/parse-resource-location)
        result-count (:count (:result metadata))
        ^ShapedRecipeBuilder builder
        (ShapedRecipeBuilder/shaped RecipeCategory/MISC result-item result-count)]
    (doseq [^String row (:pattern metadata)]
      (.pattern builder row))
    (doseq [[k spec] (:key metadata)]
      (define-key! builder k spec))
    (add-unlock-to-builder! builder recipe)
    (.save builder writer (ResourceLocation. modid/*mod-id* (str (:id recipe))))))

(defn- emit-shapeless!
  [^Consumer writer recipe]
  (let [metadata (recipe-patterns/shapeless-recipe-metadata recipe)
        result-item (metadata-resolver/resolve-item (get-in metadata [:result :item]) rl/parse-resource-location)
        result-count (:count (:result metadata))
        ^ShapelessRecipeBuilder builder
        (ShapelessRecipeBuilder/shapeless RecipeCategory/MISC result-item result-count)]
    (doseq [ingredient-spec (:ingredients metadata)]
      (.requires builder ^Ingredient (metadata-resolver/ingredient-from-spec ingredient-spec rl/parse-resource-location)))
    (add-unlock-to-builder! builder recipe)
    (.save builder writer (ResourceLocation. modid/*mod-id* (str (:id recipe))))))

(defn- emit-smelting!
  [^Consumer writer recipe]
  (let [metadata (recipe-patterns/cooking-recipe-metadata recipe)
        result-item (metadata-resolver/resolve-item (get-in metadata [:result :item]) rl/parse-resource-location)
        ingredient (metadata-resolver/ingredient-from-spec (:ingredient metadata) rl/parse-resource-location)
        experience (:experience metadata)
        cooking-time (:cooking-time metadata)
        ^SimpleCookingRecipeBuilder builder
        (SimpleCookingRecipeBuilder/smelting
          ingredient
          RecipeCategory/MISC
          result-item
          experience
          cooking-time)]
    (add-unlock-to-builder! builder recipe)
    (.save builder writer (ResourceLocation. modid/*mod-id* (str (:id recipe))))))

(defn create
  [^PackOutput pack-output ^ExistingFileHelper _exfile-helper]
  (proxy [RecipeProvider] [pack-output]
    (buildRecipes [^Consumer writer]
      (let [recipes (load-recipes)]
        (doseq [recipe recipes]
          (case (:type recipe)
            :shaped (emit-shaped! writer recipe)
            :shapeless (emit-shapeless! writer recipe)
            :smelting (emit-smelting! writer recipe)
            (throw (ex-info "Unsupported recipe type" {:recipe recipe}))))
        (println (str "[recipe-provider] generated recipes=" (count recipes)))))))
