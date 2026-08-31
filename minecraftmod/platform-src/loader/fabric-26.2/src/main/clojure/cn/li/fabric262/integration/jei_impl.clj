(ns cn.li.fabric262.integration.jei-impl
  (:require [cn.li.mc262.integration.jei-core :as jei-core]
            [cn.li.platform.neutral.integration-runtime :as hooks]
            [cn.li.platform.neutral.config :as modid])
  (:import [cn.li.mcver ResourceLocations])
  (:import [mezz.jei.api IModPlugin]
           [mezz.jei.api.registration IRecipeCategoryRegistration IRecipeRegistration IRecipeCatalystRegistration]
           [mezz.jei.api.recipe.category IRecipeCategory]
           [mezz.jei.api.gui.builder IRecipeLayoutBuilder]
           [mezz.jei.api.recipe IFocusGroup RecipeIngredientRole RecipeType]
           [mezz.jei.api.helpers IGuiHelper]
           [net.minecraft.resources Identifier]
           [net.minecraft.network.chat Component]
           [net.minecraft.world.item ItemStack]
           [java.util ArrayList]))
(defn- recipe-type ^RecipeType [m]
  (let [^Identifier id (ResourceLocations/parse ^String (:id m))]
    (RecipeType/create (.getNamespace id) (.getPath id) java.util.Map)))
(defn- category [^IGuiHelper helper m]
  (let [bg (:background m)
        ^Identifier texture (ResourceLocations/parse ^String (:texture bg))
        drawable (.createDrawable helper texture (int (:u bg)) (int (:v bg)) (int (:width bg)) (int (:height bg)))]
    (reify IRecipeCategory
      (getRecipeType [_] (recipe-type m))
      (getTitle [_] (Component/translatable ^String (:title-key m)))
      (getWidth [_] (int (:width bg)))
      (getHeight [_] (int (:height bg)))
      (getIcon [_] (jei-core/parse-item-id (:block-id m)))
      (^void setRecipe [_ ^IRecipeLayoutBuilder builder recipe ^IFocusGroup _]
        (doseq [[idx pos] (map-indexed vector (:input-slots m))
                :let [v (nth (:inputs recipe) idx nil) ^ItemStack s (when v (jei-core/parse-item-id (:item v)))] :when s]
          (.addItemStack (.addSlot builder RecipeIngredientRole/INPUT (int (:x pos)) (int (:y pos))) s))
        (doseq [[idx pos] (map-indexed vector (:output-slots m))
                :let [v (nth (:outputs recipe) idx nil) ^ItemStack s (when v (jei-core/parse-item-id (:item v)))] :when s]
          (.addItemStack (.addSlot builder RecipeIngredientRole/OUTPUT (int (:x pos)) (int (:y pos))) s))))))
(defn create-jei-plugin []
  (reify IModPlugin
    (getPluginUid [_] (ResourceLocations/parse (str modid/mod-id ":content_plugin")))
    (^void registerCategories [_ ^IRecipeCategoryRegistration r]
      (let [h (.getGuiHelper (.getJeiHelpers r))]
        (doseq [m (jei-core/get-all-categories)] (.addRecipeCategories r (into-array IRecipeCategory [(category h m)])))))
    (^void registerRecipes [_ ^IRecipeRegistration r]
      (doseq [m (jei-core/get-all-categories) :let [xs (mapv hooks/jei-format-recipe (hooks/jei-get-recipes m))]]
        (when (seq xs) (.addRecipes r (recipe-type m) (ArrayList. ^java.util.Collection xs)))))
    (^void registerRecipeCatalysts [_ ^IRecipeCatalystRegistration r]
      (doseq [m (jei-core/get-all-categories) :let [^ItemStack s (jei-core/parse-item-id (:block-id m))]]
        (when s (.addRecipeCatalyst r s (into-array RecipeType [(recipe-type m)])))))))
