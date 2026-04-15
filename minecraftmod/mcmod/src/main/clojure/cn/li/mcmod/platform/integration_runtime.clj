(ns cn.li.mcmod.platform.integration-runtime
  "Platform-neutral bridge for optional content integrations such as JEI and CraftTweaker.")

(defonce ^:private integration-hooks
  (atom {:jei-get-all-categories (fn [] [])
         :jei-get-recipes (fn [_category] [])
         :jei-format-recipe identity
         :crafttweaker-add-fusor-recipe! (fn [_input _output _energy] false)
         :crafttweaker-remove-fusor-recipe! (fn [_output-item] 0)
         :crafttweaker-add-former-recipe! (fn [_input _output _mode _energy] false)
         :crafttweaker-remove-former-recipe! (fn [_output-item _mode] 0)
         :crafttweaker-describe-recipe (fn [_recipe] "")}))

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

(defn crafttweaker-add-fusor-recipe!
  [input output energy]
  ((:crafttweaker-add-fusor-recipe! @integration-hooks) input output energy))

(defn crafttweaker-remove-fusor-recipe!
  [output-item]
  ((:crafttweaker-remove-fusor-recipe! @integration-hooks) output-item))

(defn crafttweaker-add-former-recipe!
  [input output mode energy]
  ((:crafttweaker-add-former-recipe! @integration-hooks) input output mode energy))

(defn crafttweaker-remove-former-recipe!
  [output-item mode]
  ((:crafttweaker-remove-former-recipe! @integration-hooks) output-item mode))

(defn crafttweaker-describe-recipe
  [recipe]
  ((:crafttweaker-describe-recipe @integration-hooks) recipe))