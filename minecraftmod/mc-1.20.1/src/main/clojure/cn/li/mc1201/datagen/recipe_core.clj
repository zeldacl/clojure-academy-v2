(ns cn.li.mc1201.datagen.recipe-core
  "Shared recipe datagen helpers used by Forge/Fabric providers."
  (:require [clojure.string :as str]
            [cn.li.mc1201.datagen.recipe-patterns :as recipe-patterns]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata]))

(defn load-recipes
  []
  (vec (datagen-metadata/get-recipes)))

(defn normalize-recipe-id
  [id]
  (let [sid (str id)]
    (if (str/includes? sid ":")
      (second (str/split sid #":" 2))
      sid)))

(defn ingredient-json
  [spec]
  (cond
    (:item spec) {:item (str (:item spec))}
    (:tag spec) {:tag (str (:tag spec))}
    :else (throw (ex-info "Invalid ingredient spec" {:spec spec}))))

(defn shaped-json
  [recipe]
  (let [{:keys [pattern key result]} (recipe-patterns/shaped-recipe-metadata recipe)]
    {:type "minecraft:crafting_shaped"
     :pattern (vec pattern)
     :key (into {}
                (for [[k spec] key]
                  [(str (if (char? k) k (first (str k))))
                   (ingredient-json spec)]))
     :result (cond-> {:item (str (:item result))}
               (> (int (:count result)) 1) (assoc :count (int (:count result))))}))

(defn shapeless-json
  [recipe]
  (let [{:keys [ingredients result]} (recipe-patterns/shapeless-recipe-metadata recipe)]
    {:type "minecraft:crafting_shapeless"
     :ingredients (mapv ingredient-json ingredients)
     :result (cond-> {:item (str (:item result))}
               (> (int (:count result)) 1) (assoc :count (int (:count result))))}))

(defn cooking-type
  [t]
  (case t
    :smelting "minecraft:smelting"
    :blasting "minecraft:blasting"
    :smoking "minecraft:smoking"
    :campfire-cooking "minecraft:campfire_cooking"
    (throw (ex-info "Unsupported cooking recipe type" {:type t}))))

(defn cooking-json
  [recipe]
  (let [{:keys [ingredient result experience cooking-time]} (recipe-patterns/cooking-recipe-metadata recipe)]
    {:type (cooking-type (:type recipe))
     :ingredient (ingredient-json ingredient)
     :result (str (:item result))
     :experience (float experience)
     :cookingtime (int cooking-time)}))

(defn- machine-output-json
  "Convert machine recipe output to JSON. Returns {:item ... :count ...} map."
  [output]
  (cond-> {:item (str (:item output))}
    (> (int (:count output 1)) 1)
    (assoc :count (int (:count output 1)))))

(defn imag-fusor-json
  [recipe]
  {:type "my_mod:imag_fusor"
   :input (ingredient-json (:input recipe))
   :output (machine-output-json (:output recipe))
   :consume_liquid (int (or (:consume-liquid recipe) 0))
   :craft_time (int (or (:time recipe) 200))})

(defn metal-former-json
  [recipe]
  {:type "my_mod:metal_former"
   :input (ingredient-json (:input recipe))
   :output (machine-output-json (:output recipe))
   :mode (:mode recipe)})

(defn recipe-json
  [recipe]
  (case (:type recipe)
    :shaped (shaped-json recipe)
    :shapeless (shapeless-json recipe)
    (:smelting :blasting :smoking :campfire-cooking) (cooking-json recipe)
    :imag-fusor (imag-fusor-json recipe)
    :metal-former (metal-former-json recipe)
    (throw (ex-info "Unsupported recipe type" {:type (:type recipe) :recipe recipe}))))

(defn emit-recipes!
  "Emit recipes that have a matching emitter.  Recipes whose type has no
  emitter are silently skipped (they belong to a different emission pass,
  e.g. custom types handled by the loader-specific provider).

  Returns the count of recipes that were actually emitted."
  [recipes emitters]
  (let [emitted (atom 0)]
    (doseq [recipe recipes]
      (if-let [emit-fn (get emitters (:type recipe))]
        (do (emit-fn recipe) (swap! emitted inc))
        nil))  ;; skip — handled by custom-emitters pass in loader provider
    @emitted))