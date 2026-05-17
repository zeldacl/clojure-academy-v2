(ns cn.li.ac.ability.datagen.registry
  "Datagen registry for ability domain: achievements and recipes.
   Registers metadata during datagen phase for inclusion in JSON outputs."
  (:require [cn.li.ac.achievement.registry :as achievement-registry]
            [cn.li.ac.achievement.data :as achievement-data]
            [cn.li.ac.recipe.crafting-recipes :as crafting-recipes]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(defn register-datagen-metadata!
  "Register ability domain's datagen content into shared metadata registry.
   Called during datagen initialization phase."
  []
  (let [achievement-tabs (achievement-registry/all-tabs)
      achievements achievement-data/achievements
        recipes (crafting-recipes/get-all-recipes)]
    (swap! metadata/achievement-tabs (fn [_] achievement-tabs))
    (swap! metadata/achievements (fn [_] achievements))
    (swap! metadata/recipes (fn [_] recipes))))
