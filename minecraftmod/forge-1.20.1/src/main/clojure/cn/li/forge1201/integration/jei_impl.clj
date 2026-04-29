(ns cn.li.forge1201.integration.jei-impl
  "Forge-specific JEI integration implementation.

  This namespace provides the platform-specific JEI recipe registration
  using the category metadata defined in ac.integration.jei.categories.

  JEI integration is optional - if JEI is not present, this module
  will not be loaded."
  (:require [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.config :as mod-config]
            [clojure.string :as str])
  (:import [mezz.jei.api IModPlugin]
           [mezz.jei.api.registration IRecipeCategoryRegistration
                                       IRecipeRegistration
                                       IRecipeCatalystRegistration]
           [mezz.jei.api.recipe.category IRecipeCategory]
           [mezz.jei.api.recipe IFocusGroup]
           [mezz.jei.api.gui.builder IRecipeLayoutBuilder]
           [mezz.jei.api.helpers IGuiHelper]
           [mezz.jei.api.recipe RecipeIngredientRole]
           [mezz.jei.api.recipe RecipeType]
           [mezz.jei.api.gui.builder IRecipeSlotBuilder]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level ItemLike]
           [java.util ArrayList]))

(defn- parse-item-id
  "Parse item ID string to ItemStack.
  Format: 'modid:item_name' or 'modid:item_name#count'"
  ^ItemStack [^String item-id]
  (try
    (let [[id-part count-str] (str/split item-id #"#")
          count (if count-str (Integer/parseInt count-str) 1)
          res-loc (ResourceLocation. id-part)
          item (.get BuiltInRegistries/ITEM res-loc)]
      (when item
        (ItemStack. ^ItemLike item (int count))))
    (catch Exception e
      (log/warn (str "Failed to parse item ID: " item-id " - " (ex-message e)))
      nil)))

(defn- create-recipe-category
  "Create a JEI recipe category from AC category metadata."
  [^IGuiHelper gui-helper category-meta]
  (let [{:keys [id title-key background input-slots output-slots]} category-meta
        icon-item-stack (when-let [bid (:block-id category-meta)]
                          (parse-item-id bid))
        bg-texture (ResourceLocation. (:texture background))
        bg-drawable (.createDrawable gui-helper bg-texture
                                     (int (:u background))
                                     (int (:v background))
                                     (int (:width background))
                                     (int (:height background)))]
    (reify IRecipeCategory
      (getRecipeType [_]
        ;; Return a RecipeType for this category
        (mezz.jei.api.recipe.RecipeType/create
          (.getNamespace (ResourceLocation. id))
          (.getPath (ResourceLocation. id))
          java.util.Map))

      (getTitle [_]
        (net.minecraft.network.chat.Component/translatable title-key))

      (getBackground [_]
        bg-drawable)

      (getIcon [_]
        icon-item-stack)

      (^void setRecipe [_ ^IRecipeLayoutBuilder builder recipe-map ^IFocusGroup _focuses]
        ;; recipe-map is the formatted recipe from categories/format-recipe-for-jei
        (let [inputs (get recipe-map :inputs [])
              outputs (get recipe-map :outputs [])]

          ;; Add input slots
          (doseq [[idx slot-pos] (map-indexed vector input-slots)]
            (when (< idx (count inputs))
              (let [input (nth inputs idx)
                    item-id (:item input)
                    ^ItemStack item-stack (parse-item-id item-id)]
                (when item-stack
                  (let [^IRecipeSlotBuilder slot-builder (.addSlot builder RecipeIngredientRole/INPUT
                                                                  (int (:x slot-pos))
                                                                  (int (:y slot-pos)))]
                    (.addItemStack slot-builder item-stack))))))

          ;; Add output slots
          (doseq [[idx slot-pos] (map-indexed vector output-slots)]
            (when (< idx (count outputs))
              (let [output (nth outputs idx)
                    item-id (:item output)
                    ^ItemStack item-stack (parse-item-id item-id)]
                (when item-stack
                  (let [^IRecipeSlotBuilder slot-builder (.addSlot builder RecipeIngredientRole/OUTPUT
                                                                  (int (:x slot-pos))
                                                                  (int (:y slot-pos)))]
                    (.addItemStack slot-builder item-stack))))))
          nil)))))

(defn- register-categories
  "Register all AC recipe categories with JEI."
  [^IRecipeCategoryRegistration registration]
  (try
    (let [gui-helper (.getJeiHelpers registration)
          gui-helper (.getGuiHelper gui-helper)]
      (doseq [category-meta (integration-runtime/jei-get-all-categories)]
        (let [recipe-category (create-recipe-category gui-helper category-meta)]
          (.addRecipeCategories registration (into-array IRecipeCategory [recipe-category]))
          (log/info (str "Registered JEI category: " (:id category-meta))))))
    (catch Exception e
      (log/error "Failed to register JEI categories:" (ex-message e)))))

(defn- register-recipes
  "Register all AC recipes with JEI."
  [^IRecipeRegistration registration]
  (try
    (doseq [category-meta (integration-runtime/jei-get-all-categories)]
      (let [recipes (integration-runtime/jei-get-recipes category-meta)
            ^java.util.List formatted-recipes (mapv integration-runtime/jei-format-recipe recipes)
            ^RecipeType recipe-type (mezz.jei.api.recipe.RecipeType/create
                          (.getNamespace (ResourceLocation. (:id category-meta)))
                          (.getPath (ResourceLocation. (:id category-meta)))
                          java.util.Map)]
        (when (seq formatted-recipes)
          (.addRecipes registration recipe-type (ArrayList. ^java.util.Collection formatted-recipes))
          (log/info (str "Registered " (count formatted-recipes) " recipes for " (:id category-meta))))))
    (catch Exception e
      (log/error "Failed to register JEI recipes:" (ex-message e)))))

(defn- register-catalysts
  "Register recipe catalysts (the blocks that perform the recipes)."
  [^IRecipeCatalystRegistration registration]
  (try
    (doseq [category-meta (integration-runtime/jei-get-all-categories)]
      (let [block-id (:block-id category-meta)
            ^ItemStack item-stack (parse-item-id block-id)
            ^RecipeType recipe-type (mezz.jei.api.recipe.RecipeType/create
                          (.getNamespace (ResourceLocation. (:id category-meta)))
                          (.getPath (ResourceLocation. (:id category-meta)))
                          java.util.Map)
            ^"[Lmezz.jei.api.recipe.RecipeType;" recipe-types (into-array RecipeType [recipe-type])]
        (when item-stack
          (.addRecipeCatalyst registration item-stack recipe-types)
          (log/info (str "Registered JEI catalyst: " block-id " for " (:id category-meta))))))
    (catch Exception e
      (log/error "Failed to register JEI catalysts:" (ex-message e)))))

(defn create-jei-plugin
  "Create a JEI plugin instance for AC integration.

  This returns an IModPlugin implementation that JEI will discover
  via the @JEIPlugin annotation in the Java wrapper class."
  []
  (reify IModPlugin
    (getPluginUid [_]
      (ResourceLocation. mod-config/*mod-id* "jei_plugin"))

    (registerCategories [_ registration]
      (log/info "Registering JEI categories for AcademyCraft...")
      (register-categories registration))

    (registerRecipes [_ registration]
      (log/info "Registering JEI recipes for AcademyCraft...")
      (register-recipes registration))

    (registerRecipeCatalysts [_ registration]
      (log/info "Registering JEI catalysts for AcademyCraft...")
      (register-catalysts registration))))

(defn init-jei!
  "Initialize JEI integration.

  This is called during mod initialization if JEI is present.
  The actual plugin registration happens via @JEIPlugin annotation."
  []
  (log/info "JEI integration initialized (plugin will be auto-discovered)"))

