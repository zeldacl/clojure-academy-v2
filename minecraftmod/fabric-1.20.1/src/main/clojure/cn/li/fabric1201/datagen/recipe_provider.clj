(ns cn.li.fabric1201.datagen.recipe-provider
  "Fabric 1.20.1 recipe datagen provider.

  Emits recipe JSON files from shared platform-neutral recipe metadata."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.recipe-patterns :as recipe-patterns])
  (:import [com.google.gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn- normalize-recipe-id
  [id]
  (let [sid (str id)]
    (if (str/includes? sid ":")
      (second (str/split sid #":" 2))
      sid)))

(defn- ingredient-json
  [spec]
  (cond
    (:item spec) {:item (str (:item spec))}
    (:tag spec) {:tag (str (:tag spec))}
    :else (throw (ex-info "Invalid ingredient spec" {:spec spec}))))

(defn- shaped-json
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

(defn- shapeless-json
  [recipe]
  (let [{:keys [ingredients result]} (recipe-patterns/shapeless-recipe-metadata recipe)]
    {:type "minecraft:crafting_shapeless"
     :ingredients (mapv ingredient-json ingredients)
     :result (cond-> {:item (str (:item result))}
               (> (int (:count result)) 1) (assoc :count (int (:count result))))}))

(defn- cooking-type
  [t]
  (case t
    :smelting "minecraft:smelting"
    :blasting "minecraft:blasting"
    :smoking "minecraft:smoking"
    :campfire-cooking "minecraft:campfire_cooking"
    (throw (ex-info "Unsupported cooking recipe type" {:type t}))))

(defn- cooking-json
  [recipe]
  (let [{:keys [ingredient result experience cooking-time]} (recipe-patterns/cooking-recipe-metadata recipe)]
    {:type (cooking-type (:type recipe))
     :ingredient (ingredient-json ingredient)
     :result (str (:item result))
     :experience (float experience)
     :cookingtime (int cooking-time)}))

(defn- recipe-json
  [recipe]
  (case (:type recipe)
    :shaped (shaped-json recipe)
    :shapeless (shapeless-json recipe)
    (:smelting :blasting :smoking :campfire-cooking) (cooking-json recipe)
    (throw (ex-info "Unsupported recipe type" {:type (:type recipe) :recipe recipe}))))

(defn create-provider
  [output]
  (let [^String mod-id (str modid/*mod-id*)
  path-provider (.createPathProvider output PackOutput$Target/DATA_PACK "recipes")
        gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [recipes (vec (datagen-metadata/get-recipes))
              writes (atom [])]
          (doseq [recipe recipes]
            (let [recipe-id (normalize-recipe-id (:id recipe))
                  target-path (.json ^PackOutput$PathProvider path-provider (ResourceLocation. mod-id recipe-id))
              json-tree (.toJsonTree gson (gson-util/normalize-json (recipe-json recipe)))]
              (swap! writes conj
                     (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str mod-id " Recipe Provider")))))
