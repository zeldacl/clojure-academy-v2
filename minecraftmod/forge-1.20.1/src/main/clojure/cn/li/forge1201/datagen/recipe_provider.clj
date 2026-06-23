(ns cn.li.forge1201.datagen.recipe-provider
  "Forge 1.20.1 recipe datagen provider."
  (:require [cn.li.mc1201.datagen.recipe-core :as recipe-core]
            [cn.li.mc1201.datagen.recipe-provider-core :as recipe-provider-core]
            [cn.li.forge1201.datagen.recipe-provider-custom :as recipe-provider-custom])
  (:import [java.util.function Consumer]
           [net.minecraft.data PackOutput]
           [net.minecraft.data.recipes RecipeProvider]
           [net.minecraftforge.common.data ExistingFileHelper]))

(defn create
  [^PackOutput pack-output ^ExistingFileHelper _exfile-helper]
  (proxy [RecipeProvider] [pack-output]
    (buildRecipes [^Consumer writer]
      (let [vanilla-emitted (recipe-provider-core/build-recipes! writer)
            recipes (recipe-core/load-recipes)
            custom-emitters (recipe-provider-custom/custom-emitters writer)
            custom-recipes (filter #(contains? custom-emitters (:type %)) recipes)
            custom-emitted (recipe-core/emit-recipes! custom-recipes custom-emitters)]
        (println (str "[recipe-provider] generated recipes: vanilla=" vanilla-emitted
                      " custom=" custom-emitted))))))
