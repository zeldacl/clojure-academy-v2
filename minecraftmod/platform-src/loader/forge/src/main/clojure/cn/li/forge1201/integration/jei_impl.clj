(ns cn.li.forge1201.integration.jei-impl
  "Forge-specific JEI integration implementation.

  This namespace provides the platform-specific JEI recipe registration
  using the shared Minecraft-version core logic from platform-src/minecraft/mc-1.20.1.
  Delegates ~75% of logic to jei_core; keeps only Forge-specific plugin
  registration and JEI API integration.

  JEI integration is optional - if JEI is not present, this module
  will not be loaded."
  (:require [cn.li.mc1201.integration.jei-core :as jei-core]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.integration.runtime-hooks :as integration-hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.config :as mod-config])
  (:import [cn.li.mc1201.runtime RuntimeAccessShared]
           [mezz.jei.api IModPlugin]
           [mezz.jei.api.registration IRecipeCategoryRegistration
                                       IRecipeRegistration
                                       IRecipeCatalystRegistration
                                       ISubtypeRegistration]
           [mezz.jei.api.recipe.category IRecipeCategory]
           [mezz.jei.api.recipe IFocusGroup]
           [mezz.jei.api.gui.builder IRecipeLayoutBuilder]
           [mezz.jei.api.helpers IGuiHelper]
           [mezz.jei.api.recipe RecipeIngredientRole]
           [mezz.jei.api.recipe RecipeType]
           [mezz.jei.api.gui.builder IRecipeSlotBuilder]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item ItemStack]
           [java.util ArrayList]))

;; ============================================================================
;; Recipe Category Creation (Forge-Specific)
;; ============================================================================

(defn- create-recipe-category
  "Create a JEI recipe category from AC category metadata (Forge version).
  
  Delegates data retrieval to jei_core; implements IRecipeCategory
  using Forge's JEI registration APIs."
  [^IGuiHelper gui-helper category-meta]
  (let [{:keys [id title-key background input-slots output-slots]} category-meta
        icon-item-stack (when-let [bid (:block-id category-meta)]
                          (jei-core/parse-item-id bid))
        bg-texture (ResourceLocation. (:texture background))
        bg-drawable (.createDrawable gui-helper bg-texture
                                     (int (:u background))
                                     (int (:v background))
                                     (int (:width background))
                                     (int (:height background)))]
    (reify IRecipeCategory
      (getRecipeType [_]
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
        (let [inputs (get recipe-map :inputs [])
              outputs (get recipe-map :outputs [])]

          ;; Add input slots
          (doseq [[idx slot-pos] (map-indexed vector input-slots)]
            (when (< idx (count inputs))
              (let [input (nth inputs idx)
                    item-id (:item input)
                    ^ItemStack item-stack (jei-core/parse-item-id item-id)]
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
                    ^ItemStack item-stack (jei-core/parse-item-id item-id)]
                (when item-stack
                  (let [^IRecipeSlotBuilder slot-builder (.addSlot builder RecipeIngredientRole/OUTPUT
                                                                  (int (:x slot-pos))
                                                                  (int (:y slot-pos)))]
                    (.addItemStack slot-builder item-stack))))))
          nil)))))

;; ============================================================================
;; Recipe Registration Functions
;; ============================================================================

(defn- register-categories
  "Register all AC recipe categories with JEI."
  [^IRecipeCategoryRegistration registration]
  (try
    (let [gui-helper (.getJeiHelpers registration)
          gui-helper (.getGuiHelper gui-helper)]
      (doseq [category-meta (integration-hooks/jei-get-all-categories)]
        (let [recipe-category (create-recipe-category gui-helper category-meta)]
          (.addRecipeCategories registration (into-array IRecipeCategory [recipe-category]))
          (log/info (str "Registered JEI category: " (:id category-meta))))))
    (catch Exception e
      (log/error "Failed to register JEI categories:" (ex-message e)))))

(defn- register-recipes
  "Register all AC recipes with JEI."
  [^IRecipeRegistration registration]
  (try
    (doseq [category-meta (integration-hooks/jei-get-all-categories)]
      (let [recipes (integration-hooks/jei-get-recipes category-meta)
            ^java.util.List formatted-recipes (mapv integration-hooks/jei-format-recipe recipes)
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
    (doseq [category-meta (integration-hooks/jei-get-all-categories)]
      (let [block-id (:block-id category-meta)
            ^ItemStack item-stack (jei-core/parse-item-id block-id)
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

(defn- register-item-subtypes
  "Register subtype handling for items that use NBT/stateful variants in creative tab.

  These items intentionally expose multiple ItemStack states in one creative tab
  entry stream (empty/full, unfilled/filled). Tell JEI to use NBT as subtype key
  so variants are not treated as duplicates."
  [^ISubtypeRegistration registration]
  (try
    (let [item-ids (integration-hooks/get-jei-nbt-subtype-item-ids)
          items (->> item-ids
                     (map registry-state/get-registered-item)
                     (remove nil?)
                     vec)]
      (when (seq items)
        (let [item-class (RuntimeAccessShared/getItemClass)
              ^"[Lnet.minecraft.world.item.Item;" item-array (into-array item-class items)]
          (.useNbtForSubtypes ^ISubtypeRegistration registration item-array))
        (log/info "Registered JEI NBT subtypes for" (count items) "creative-tab variant items.")))
    (catch Exception e
      (log/error "Failed to register JEI item subtypes:" (ex-message e)))))

;; ============================================================================
;; JEI Plugin Interface (Forge-Specific)
;; ============================================================================

(defn create-jei-plugin
  "Create a JEI plugin instance for descriptor-provided content integration (Forge).

  This returns an IModPlugin implementation that JEI will discover
  via the @JEIPlugin annotation in the Java wrapper class."
  []
  (reify IModPlugin
    (getPluginUid [_]
      (ResourceLocation. mod-config/mod-id "content_plugin"))

    (registerCategories [_ registration]
      (log/info "Registering JEI categories for content descriptors...")
      (register-categories registration))

    (registerRecipes [_ registration]
      (log/info "Registering JEI recipes for content descriptors...")
      (register-recipes registration))

    (registerItemSubtypes [_ registration]
      (register-item-subtypes registration))

    (registerRecipeCatalysts [_ registration]
      (log/info "Registering JEI catalysts for content descriptors...")
      (register-catalysts registration))))

(defn init-jei!
  "Initialize JEI integration (Forge version).

  This is called during mod initialization if JEI is present.
  The actual plugin registration happens via @JEIPlugin annotation."
  []
  (log/info "JEI integration initialized (plugin will be auto-discovered)"))
