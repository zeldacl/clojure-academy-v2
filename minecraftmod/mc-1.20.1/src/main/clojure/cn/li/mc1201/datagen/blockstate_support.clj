(ns cn.li.mc1201.datagen.blockstate-support
  "Shared helpers for blockstate/model/item-model datagen JSON payloads."
  (:require [clojure.string :as str]))

(def ^:private texture-aliases
  {"my_mod:block/wind_gen_main" "my_mod:block/windgen_main"
   "my_mod:block/wind_gen_base" "my_mod:block/windgen_base"
   "my_mod:block/wind_gen_pillar" "my_mod:block/windgen_pillar"
   "my_mod:block/converter_rf_input" "my_mod:block/rf_input"
   "my_mod:block/converter_rf_output" "my_mod:block/rf_output"
   "my_mod:block/converter_eu_input" "my_mod:block/eu_input"
   "my_mod:block/converter_eu_output" "my_mod:block/eu_output"
   "my_mod:block/constrained_ore" "my_mod:block/constraint_metal"
   "my_mod:block/imaginary_ore" "my_mod:block/imagsil_ore"
   "my_mod:block/developer_normal" "my_mod:block/dev_normal"
   "my_mod:block/developer_advanced" "my_mod:block/dev_advanced"
   "my_mod:block/developer_normal_part" "my_mod:block/dev_normal"
   "my_mod:block/developer_advanced_part" "my_mod:block/dev_advanced"
   "my_mod:block/ability_interferer" "my_mod:block/ability_interf_off"
   "my_mod:block/imag_fusor" "my_mod:block/machine_frame"
   "my_mod:block/metal_former" "my_mod:block/metal_former_front"})

(defn normalize-texture-id
  [texture]
  (let [s (str texture)]
    (get texture-aliases s s)))

(defn model-id->model-name
  [model-id]
  (str/replace (str model-id) #".*:block/" ""))

(defn- normalize-condition-value
  [v]
  (cond
    (keyword? v) (name v)
    :else (str v)))

(defn simple-blockstate-json
  [model-id]
  {:variants
   {"" {:model (str model-id)}}})

(defn multipart-blockstate-json
  [parts]
  {:multipart
   (mapv (fn [{:keys [condition models]}]
           (let [entry {:apply {:model (str (first models))}}]
             (if (map? condition)
               (assoc entry :when (into {}
                                        (map (fn [[k v]] [(name k) (normalize-condition-value v)]))
                                        condition))
               entry)))
         parts)})

(defn cube-all-model-json
  [texture-all]
  {:parent "minecraft:block/cube_all"
   :textures {:all (normalize-texture-id texture-all)}})

(defn cube-model-json
  [{:keys [down up north south east west]}]
  {:parent "minecraft:block/cube"
   :textures {:down (normalize-texture-id down)
              :up (normalize-texture-id up)
              :north (normalize-texture-id north)
              :south (normalize-texture-id south)
              :east (normalize-texture-id east)
              :west (normalize-texture-id west)}})

(defn parent-model-json
  ([parent]
   {:parent (str parent)})
  ([parent textures]
   (cond-> {:parent (str parent)}
     (seq textures) (assoc :textures (into {}
                                           (map (fn [[k v]] [(name k) (normalize-texture-id v)]))
                                           textures)))))

(defn flat-item-model-json
  [layer0]
  {:parent "minecraft:item/generated"
   :textures {:layer0 (normalize-texture-id layer0)}})
