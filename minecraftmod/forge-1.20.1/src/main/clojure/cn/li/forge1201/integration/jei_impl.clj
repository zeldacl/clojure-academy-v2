(ns cn.li.forge1201.integration.jei-impl
  "Forge-specific JEI integration implementation.

  This namespace provides the platform-specific JEI recipe registration
  using the category metadata defined in ac.integration.jei.categories.

  JEI integration is optional - if JEI is not present, this module
  will not be loaded."
  (:require [cn.li.ac.integration.jei.categories :as categories]
            [cn.li.mcmod.util.log :as log]
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
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level ItemLike]))

(set! *warn-on-reflection* false)

(defn- parse-item-id
  "Parse item ID string to ItemStack.
  Format: 'modid:item_name' or 'modid:item_name#count'"
  [^String item-id]
  (try
    (let [[id-part count-str] (str/split item-id #"#")
          count (if count-str (Integer/parseInt count-str) 1)
          res-loc (ResourceLocation. id-part)
          regs-cls (Class/forName "net.minecraft.core.registries.BuiltInRegistries")
          item-field (.getField regs-cls "ITEM")
          item-registry (.get item-field nil)
          get-method (.getMethod (class item-registry) "get" (into-array Class [Object]))
          item (.invoke get-method item-registry (object-array [res-loc]))]
      (when item
        (ItemStack. ^ItemLike item (int count))))
    (catch Exception e
      (log/warn (str "Failed to parse item ID: " item-id " - " (ex-message e)))
      nil)))

(defn- create-recipe-category
  "Create a JEI recipe category from AC category metadata."
  [^IGuiHelper gui-helper category-meta]
  (let [{:keys [id title-key background input-slots output-slots]} category-meta
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
        ;; TODO: Translate title-key
        (net.minecraft.network.chat.Component/literal title-key))

      (getBackground [_]
        bg-drawable)

      (getIcon [_]
        ;; TODO: Get block icon from registry
        nil)

      (^void setRecipe [_ ^IRecipeLayoutBuilder builder recipe-map ^IFocusGroup _focuses]
        ;; recipe-map is the formatted recipe from categories/format-recipe-for-jei
        (let [inputs (get recipe-map :inputs [])
              outputs (get recipe-map :outputs [])]

          ;; Add input slots
          (doseq [[idx slot-pos] (map-indexed vector input-slots)]
            (when (< idx (count inputs))
              (let [input (nth inputs idx)
                    item-id (:item input)
                    item-stack (parse-item-id item-id)]
                (when item-stack
                  (-> builder
                      (.addSlot RecipeIngredientRole/INPUT
                               (int (:x slot-pos))
                               (int (:y slot-pos)))
                      (.addItemStack item-stack))))))

          ;; Add output slots
          (doseq [[idx slot-pos] (map-indexed vector output-slots)]
            (when (< idx (count outputs))
              (let [output (nth outputs idx)
                    item-id (:item output)
                    item-stack (parse-item-id item-id)]
                (when item-stack
                  (-> builder
                      (.addSlot RecipeIngredientRole/OUTPUT
                               (int (:x slot-pos))
                               (int (:y slot-pos)))
                      (.addItemStack item-stack))))))
          nil)))))

(defn- register-categories
  "Register all AC recipe categories with JEI."
  [^IRecipeCategoryRegistration registration]
  (try
    (let [gui-helper (.getJeiHelpers registration)
          gui-helper (.getGuiHelper gui-helper)]
      (doseq [category-meta categories/all-categories]
        (let [recipe-category (create-recipe-category gui-helper category-meta)]
          (.addRecipeCategories registration (into-array IRecipeCategory [recipe-category]))
          (log/info (str "Registered JEI category: " (:id category-meta))))))
    (catch Exception e
      (log/error "Failed to register JEI categories:" (ex-message e)))))

(defn- register-recipes
  "Register all AC recipes with JEI."
  [^IRecipeRegistration registration]
  (try
    (doseq [category-meta categories/all-categories]
      (let [recipes (categories/get-recipes-for-category category-meta)
            formatted-recipes (map categories/format-recipe-for-jei recipes)
            recipe-type (mezz.jei.api.recipe.RecipeType/create
                          (.getNamespace (ResourceLocation. (:id category-meta)))
                          (.getPath (ResourceLocation. (:id category-meta)))
                          java.util.Map)]
        (when (seq formatted-recipes)
          (.addRecipes registration recipe-type (java.util.ArrayList. formatted-recipes))
          (log/info (str "Registered " (count formatted-recipes) " recipes for " (:id category-meta))))))
    (catch Exception e
      (log/error "Failed to register JEI recipes:" (ex-message e)))))

(defn- register-catalysts
  "Register recipe catalysts (the blocks that perform the recipes)."
  [^IRecipeCatalystRegistration registration]
  (try
    (doseq [category-meta categories/all-categories]
      (let [block-id (:block-id category-meta)
            item-stack (parse-item-id block-id)
            recipe-type (mezz.jei.api.recipe.RecipeType/create
                          (.getNamespace (ResourceLocation. (:id category-meta)))
                          (.getPath (ResourceLocation. (:id category-meta)))
                          java.util.Map)]
        (when item-stack
          (.addRecipeCatalyst registration recipe-type item-stack)
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
      (ResourceLocation. "academycraft" "jei_plugin"))

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
