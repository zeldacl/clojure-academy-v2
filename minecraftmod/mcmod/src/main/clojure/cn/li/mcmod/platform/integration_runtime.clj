(ns cn.li.mcmod.platform.integration-runtime
  "Platform-neutral bridge for optional content integrations such as JEI and CraftTweaker.")

(defonce ^:private integration-hooks
  (atom {:jei-get-all-categories (fn [] [])
         :jei-get-recipes (fn [_category] [])
         :jei-format-recipe identity
         :describe-recipe (fn [_recipe] "")}))

(defn register-integration-hooks!
  [hooks]
  (swap! integration-hooks merge hooks)
  nil)

(defn jei-get-all-categories
  []
  ((:jei-get-all-categories @integration-hooks)))

(defn jei-get-recipes
  [category]
  ((:jei-get-recipes @integration-hooks) category))

(defn jei-format-recipe
  [recipe]
  ((:jei-format-recipe @integration-hooks) recipe))

(defn describe-recipe
  [recipe]
  ((:describe-recipe @integration-hooks) recipe))