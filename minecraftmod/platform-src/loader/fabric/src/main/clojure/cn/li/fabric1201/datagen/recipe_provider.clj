(ns cn.li.fabric1201.datagen.recipe-provider
  "Fabric 1.20.1 recipe datagen provider.

  Uses shared DelegatingRecipeProvider + vanilla/custom emitters (ContentRecipe)."
  (:require [cn.li.mc1201.datagen.recipe-core :as recipe-core]
            [cn.li.mc1201.datagen.recipe-provider-core :as recipe-provider-core]
            [cn.li.mc1201.datagen.recipe-provider-custom :as recipe-provider-custom])
  (:import [cn.li.mc1201.shim DelegatingRecipeProvider]
           [java.util.function Consumer]
           [net.minecraft.data PackOutput]))

(defn create-provider
  [^PackOutput output]
  (DelegatingRecipeProvider.
    output
    (fn [_this ^Consumer writer]
      (let [vanilla-emitted (recipe-provider-core/build-recipes! writer)
            recipes (recipe-core/load-recipes)
            custom-emitters (recipe-provider-custom/custom-emitters writer)
            custom-recipes (filter #(contains? custom-emitters (:type %)) recipes)
            custom-emitted (recipe-core/emit-recipes! custom-recipes custom-emitters)]
        (println (str "[fabric-recipe-provider] generated recipes: vanilla=" vanilla-emitted
                      " custom=" custom-emitted))))))
