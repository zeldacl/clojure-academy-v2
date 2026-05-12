(ns cn.li.forge1201.datagen.recipe-provider
  "Forge 1.20.1 recipe datagen provider."
  (:require [cn.li.mc1201.datagen.recipe-provider-core :as recipe-provider-core])
  (:import [java.util.function Consumer]
           [net.minecraft.data PackOutput]
           [net.minecraft.data.recipes RecipeProvider]
           [net.minecraftforge.common.data ExistingFileHelper]))

(defn create
  [^PackOutput pack-output ^ExistingFileHelper _exfile-helper]
  (proxy [RecipeProvider] [pack-output]
    (buildRecipes [^Consumer writer]
      (let [emitted (recipe-provider-core/build-recipes! writer)]
        (println (str "[recipe-provider] generated recipes=" emitted))))))
