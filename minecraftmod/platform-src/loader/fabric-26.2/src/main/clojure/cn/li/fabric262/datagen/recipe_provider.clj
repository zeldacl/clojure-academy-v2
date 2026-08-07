(ns cn.li.fabric262.datagen.recipe-provider
  "Fabric 26.2 recipe datagen provider shell."
  (:require [cn.li.mcbase.datagen.recipe-core :as recipe-core]
            [cn.li.mc262.datagen.recipe-provider-core :as mc-recipe-provider]
            [cn.li.mc262.datagen.recipe-provider-custom :as recipe-provider-custom]
            [cn.li.platform.datagen.recipe-provider-core :as recipe-provider-core])
  (:import [cn.li.mc262.shim DelegatingRecipeProvider]
           [java.util.function Consumer]
           [net.minecraft.data PackOutput]))

(def ^:private recipe-deps
  {:build-vanilla! mc-recipe-provider/build-recipes!
   :load-recipes recipe-core/load-recipes
   :custom-emitters recipe-provider-custom/custom-emitters
   :emit-recipes! recipe-core/emit-recipes!
   :log-label "fabric-recipe-provider"})

(defn create-provider
  [^PackOutput output]
  ;; 26.2 RecipeProvider is constructed by RecipeProvider.Runner with a
  ;; HolderLookup.Provider and RecipeOutput; the old two-argument Fabric shim
  ;; no longer matches. Datagen registration remains a loader seam until it
  ;; is migrated to Runner.
  nil)
