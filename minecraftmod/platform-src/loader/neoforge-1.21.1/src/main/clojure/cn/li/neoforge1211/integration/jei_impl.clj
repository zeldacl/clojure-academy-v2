(ns cn.li.neoforge1211.integration.jei-impl
  "NeoForge 1.21.1 JEI adapter. The recipe model remains neutral; this
  namespace owns only typed JEI API calls and is loaded by the annotated Java
  discovery wrapper when JEI is installed."
  (:require [cn.li.mc1211.integration.jei-core :as jei-core]
            [cn.li.platform.neutral.integration-runtime :as integration-hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.neoforgebase.registry.state :as registry-state]
            [cn.li.platform.neutral.config :as modid])
  (:import [mezz.jei.api IModPlugin]
           [mezz.jei.api.registration IRecipeCategoryRegistration IRecipeRegistration IRecipeCatalystRegistration]
           [mezz.jei.api.recipe.category IRecipeCategory]
           [mezz.jei.api.gui.builder IRecipeLayoutBuilder]
           [mezz.jei.api.recipe IFocusGroup RecipeIngredientRole RecipeType]
           [mezz.jei.api.helpers IGuiHelper]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.network.chat Component]
           [net.minecraft.world.item ItemStack]
           [java.util ArrayList]))

(defn- recipe-type ^RecipeType [category]
  (let [^ResourceLocation id (ResourceLocation. ^String (:id category))]
    (RecipeType/create (.getNamespace id) (.getPath id) java.util.Map)))

(defn- category [^IGuiHelper helper category-meta]
  (let [bg (:background category-meta)
        ^ResourceLocation texture (ResourceLocation. ^String (:texture bg))
        drawable (.createDrawable helper texture (int (:u bg)) (int (:v bg))
                                   (int (:width bg)) (int (:height bg)))
        icon (when-let [id (:block-id category-meta)] (jei-core/parse-item-id id))]
    (reify IRecipeCategory
      (getRecipeType [_] (recipe-type category-meta))
      (getTitle [_] (Component/translatable ^String (:title-key category-meta)))
      (getBackground [_] drawable)
      (getIcon [_] icon)
      (^void setRecipe [_ ^IRecipeLayoutBuilder builder recipe-map ^IFocusGroup _]
        (doseq [[idx pos] (map-indexed vector (:input-slots category-meta))
                :let [value (nth (:inputs recipe-map) idx nil)
                      ^ItemStack stack (when value (jei-core/parse-item-id (:item value)))]
                :when stack]
          (.addItemStack (.addSlot builder RecipeIngredientRole/INPUT (int (:x pos)) (int (:y pos))) stack))
        (doseq [[idx pos] (map-indexed vector (:output-slots category-meta))
                :let [value (nth (:outputs recipe-map) idx nil)
                      ^ItemStack stack (when value (jei-core/parse-item-id (:item value)))]
                :when stack]
          (.addItemStack (.addSlot builder RecipeIngredientRole/OUTPUT (int (:x pos)) (int (:y pos))) stack))))))

(defn- categories [] (jei-core/get-all-categories))

(defn create-jei-plugin []
  (reify IModPlugin
    (getPluginUid [_] (ResourceLocation. ^String modid/mod-id "content_plugin"))
    (registerCategories [_ ^IRecipeCategoryRegistration registration]
      (let [^IGuiHelper helper (.getGuiHelper (.getJeiHelpers registration))]
        (doseq [meta (categories)]
          (.addRecipeCategories registration (into-array IRecipeCategory [(category helper meta)]))))
      nil)
    (registerRecipes [_ ^IRecipeRegistration registration]
      (doseq [meta (categories)
              :let [recipes (mapv integration-hooks/jei-format-recipe
                                   (integration-hooks/jei-get-recipes meta))]]
        (when (seq recipes)
          (.addRecipes registration (recipe-type meta) (ArrayList. ^java.util.Collection recipes))))
      nil)
    (registerRecipeCatalysts [_ ^IRecipeCatalystRegistration registration]
      (doseq [meta (categories)
              :let [^ItemStack stack (jei-core/parse-item-id (:block-id meta))]]
        (when stack
          (.addRecipeCatalyst registration stack (into-array RecipeType [(recipe-type meta)]))))
      nil)))

(defn init-jei! []
  (log/debug "NeoForge JEI integration initialized"))
