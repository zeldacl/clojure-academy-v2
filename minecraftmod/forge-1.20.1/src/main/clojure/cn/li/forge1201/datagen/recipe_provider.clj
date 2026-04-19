(ns cn.li.forge1201.datagen.recipe-provider
  "Forge 1.20.1 recipe datagen provider."
  (:require [clojure.string :as str]
            [cn.li.forge1201.datagen.resource-location :as rl]
            [cn.li.mcmod.config :as modid])
  (:import [java.util.function Consumer]
           [net.minecraft.advancements CriterionTriggerInstance]
           [net.minecraft.advancements.critereon InventoryChangeTrigger$TriggerInstance]
           [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.data PackOutput]
           [net.minecraft.data.recipes RecipeCategory RecipeProvider
            ShapedRecipeBuilder ShapelessRecipeBuilder SimpleCookingRecipeBuilder]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.tags TagKey]
           [net.minecraft.world.item Item Items]
           [net.minecraft.world.item.crafting Ingredient]
           [net.minecraft.world.level ItemLike]
           [net.minecraftforge.common.data ExistingFileHelper]))

(defn- load-recipes
  []
  (if-let [get-all-recipes (requiring-resolve 'cn.li.ac.recipe.crafting-recipes/get-all-recipes)]
    (vec (get-all-recipes))
    []))

(defn- resolve-item
  ^ItemLike [item-id]
  (let [^ResourceLocation item-rl (rl/parse-resource-location item-id)
        ^Item item (.get BuiltInRegistries/ITEM item-rl)]
    (when (or (nil? item) (= item Items/AIR))
      (throw (ex-info "Unknown item id in recipe metadata" {:item-id item-id})))
    item))

(defn- resolve-tag
  ^TagKey [tag-id]
  (let [^ResourceLocation tag-rl (rl/parse-resource-location tag-id)]
    (TagKey/create Registries/ITEM tag-rl)))

(defn- ingredient-from-spec
  ^Ingredient [spec]
  (cond
    (:item spec)
    (let [^"[Lnet.minecraft.world.level.ItemLike;" item-array
          (into-array ItemLike [(resolve-item (:item spec))])]
      (Ingredient/of item-array))

    (:tag spec)
    (Ingredient/of ^TagKey (resolve-tag (:tag spec)))

    :else
    (throw (ex-info "Invalid ingredient spec" {:spec spec}))))

(defn- define-key!
  [^ShapedRecipeBuilder builder k spec]
  (let [ch (if (char? k) k (first (str k)))
        ^Character key-char (Character/valueOf (char ch))
        ingredient (ingredient-from-spec spec)]
    (.define builder key-char ingredient)))

(defn- first-item-id
  [recipe]
  (or (some :item (vals (:key recipe)))
      (some :item (:ingredients recipe))
      (get-in recipe [:ingredient :item])
      (get-in recipe [:result :item])))

(defn- unlock-name
  [item-id]
  (let [path (or (second (str/split (str item-id) #":" 2))
                 (str item-id))]
    (str "has_" (str/replace path #"[^\w/.-]" "_"))))

(defn- criterion-for-item
  ^CriterionTriggerInstance
  [item-id]
  (let [^ItemLike item (resolve-item item-id)]
    (InventoryChangeTrigger$TriggerInstance/hasItems
      ^"[Lnet.minecraft.world.level.ItemLike;"
      (into-array ItemLike [item]))))

(defn- add-unlock-shaped!
  [^ShapedRecipeBuilder builder recipe]
  (when-let [item-id (first-item-id recipe)]
    (.unlockedBy builder
      ^String (unlock-name item-id)
      ^CriterionTriggerInstance (criterion-for-item item-id)))
  builder)

(defn- add-unlock-shapeless!
  [^ShapelessRecipeBuilder builder recipe]
  (when-let [item-id (first-item-id recipe)]
    (.unlockedBy builder
      ^String (unlock-name item-id)
      ^CriterionTriggerInstance (criterion-for-item item-id)))
  builder)

(defn- add-unlock-smelting!
  [^SimpleCookingRecipeBuilder builder recipe]
  (when-let [item-id (first-item-id recipe)]
    (.unlockedBy builder
      ^String (unlock-name item-id)
      ^CriterionTriggerInstance (criterion-for-item item-id)))
  builder)

(defn- emit-shaped!
  [^Consumer writer recipe]
  (let [result-item (resolve-item (get-in recipe [:result :item]))
        result-count (int (or (get-in recipe [:result :count]) 1))
        ^ShapedRecipeBuilder builder
        (ShapedRecipeBuilder/shaped RecipeCategory/MISC result-item result-count)]
    (doseq [^String row (:pattern recipe)]
      (.pattern builder row))
    (doseq [[k spec] (:key recipe)]
      (define-key! builder k spec))
    (add-unlock-shaped! builder recipe)
    (.save builder writer (ResourceLocation. modid/*mod-id* (str (:id recipe))))))

(defn- emit-shapeless!
  [^Consumer writer recipe]
  (let [result-item (resolve-item (get-in recipe [:result :item]))
        result-count (int (or (get-in recipe [:result :count]) 1))
        ^ShapelessRecipeBuilder builder
        (ShapelessRecipeBuilder/shapeless RecipeCategory/MISC result-item result-count)]
    (doseq [ingredient-spec (:ingredients recipe)]
      (.requires builder ^Ingredient (ingredient-from-spec ingredient-spec)))
    (add-unlock-shapeless! builder recipe)
    (.save builder writer (ResourceLocation. modid/*mod-id* (str (:id recipe))))))

(defn- emit-smelting!
  [^Consumer writer recipe]
  (let [result-item (resolve-item (get-in recipe [:result :item]))
        ingredient (ingredient-from-spec (:ingredient recipe))
        experience (float (or (:experience recipe) 0.0))
        cooking-time (int (or (:cooking-time recipe) 200))
        ^SimpleCookingRecipeBuilder builder
        (SimpleCookingRecipeBuilder/smelting
          ingredient
          RecipeCategory/MISC
          result-item
          experience
          cooking-time)]
    (add-unlock-smelting! builder recipe)
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
