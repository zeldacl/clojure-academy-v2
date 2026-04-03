(ns cn.li.ac.block.metal-former.recipes
  "Metal Former recipe system")

(def recipes
  "Built-in recipes for Metal Former"
  [])

(defn find-recipe
  "Find a recipe matching the given input"
  [input-item]
  nil)

(defn get-recipe-by-id
  "Get a recipe by its ID"
  [recipe-id]
  (first (filter #(= (:id %) recipe-id) recipes)))

(defn can-form?
  "Check if a recipe can be formed with current input and energy"
  [recipe input-item energy]
  (and recipe
       (>= energy (:energy recipe 0.0))
       true))
