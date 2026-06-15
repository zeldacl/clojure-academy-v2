(ns cn.li.mc1201.datagen.item-model-patterns
  "Shared item model metadata helpers for datagen.

  Keeps pure data selection and naming logic loader-agnostic so Forge/Fabric
  providers can share the same model discovery rules."
  (:require [clojure.string :as str]))

(defn registry-model-basename
  "Normalize an item id into a safe model basename."
  [item-id]
  (str/replace (str item-id) #"-" "_"))

(defn energy-tier-model-spec
  "Build derived model names for energy-tier items.

  Returns a map with keys:
  - :base
  - :empty-texture
  - :half-texture
  - :full-texture
  - :half-model
  - :full-model"
  [item-id {:keys [texture-empty texture-half texture-full]}]
  (let [base (registry-model-basename item-id)]
    {:base base
     :empty-texture (or texture-empty (str base "_empty"))
     :half-texture (or texture-half (str base "_half"))
     :full-texture (or texture-full (str base "_full"))
     :half-model (str base "_half")
     :full-model (str base "_full")}))

(defn simple-model-spec
  "Normalize a simple item model spec into a portable map."
  [item-name item-spec]
  (when-let [texture (get-in item-spec [:properties :model-texture])]
    {:item-name (str item-name)
     :model-texture texture
     :model-parent (get-in item-spec [:properties :model-parent] "item/generated")}))

(defn energy-tier-item?
  "Predicate: does an item spec request energy-tier models?"
  [item-spec]
  (boolean (get-in item-spec [:properties :item-model-energy-levels])))

(defn obj-3d-item?
  "Predicate: does an item spec request a forge:obj 3D model?"
  [item-spec]
  (boolean (get-in item-spec [:properties :item-model-3d-obj])))

(defn obj-3d-model-spec
  "Build the 3D OBJ model spec from item DSL properties.
  Returns a map with :model-name (suffixed with _3d), :obj-model, :texture, :display."
  [item-id {:keys [obj-model texture display]}]
  (let [base (registry-model-basename item-id)]
    {:model-name (str base "_3d")
     :obj-model (or obj-model (str "models/" base ".obj"))
     :texture (or texture (str "models/" base))
     :display (or display {})}))
