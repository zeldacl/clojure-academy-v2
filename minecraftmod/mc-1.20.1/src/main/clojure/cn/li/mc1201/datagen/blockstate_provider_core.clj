(ns cn.li.mc1201.datagen.blockstate-provider-core
  "Shared blockstate/model/item-model datagen inference helpers."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.block.blockstate-definition :as blockstate-def]
            [cn.li.mc1201.datagen.blockstate-support :as bs-support]))

(defn normalize-candidates
  [s]
  (when (and s (not (str/blank? s)))
    [s
     (str/replace s "-" "_")
     (str/replace s "_" "-")]))

(defn registry-name->block-spec
  [registry-name]
  (let [candidates (distinct (normalize-candidates registry-name))]
    (or
     (some (fn [candidate]
             (registry-metadata/get-block-spec (keyword candidate)))
           candidates)
     (some (fn [block-id]
             (when (some #{(registry-metadata/get-block-registry-name block-id)} candidates)
               (registry-metadata/get-block-spec block-id)))
           (registry-metadata/get-all-block-ids)))))

(defn item-texture-from-spec
  [registry-name]
  (let [block-spec (registry-name->block-spec registry-name)]
    (or (get-in block-spec [:rendering :item-texture])
        (get-in block-spec [:rendering :texture])
        (str modid/*mod-id* ":block/" registry-name))))

(defn block-texture-from-spec
  [registry-name]
  (let [block-spec (registry-name->block-spec registry-name)]
    (or (get-in block-spec [:rendering :textures :all])
        (get-in block-spec [:rendering :texture])
        (str modid/*mod-id* ":block/" registry-name))))

(defn block-parent-from-spec
  [registry-name]
  (let [block-spec (registry-name->block-spec registry-name)]
    (or (get-in block-spec [:rendering :model-parent])
        "minecraft:block/cube_all")))

(defn model-id->model-name
  [model-id]
  (bs-support/model-id->model-name model-id))

(defn model-id->registry-name
  [model-name]
  (let [from-node (when (try
                          (requiring-resolve 'cn.li.ac.block.wireless-node.blockstate/get-node-model-texture-config)
                          true
                          (catch Throwable _ false))
                    (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.blockstate/get-node-model-texture-config)]
                      (when (f model-name)
                        (second (re-find #"^(node_[^_]+)" model-name)))))]
    (or from-node
        model-name)))

(defn block-model-spec
  [model-name]
  (if-let [cube-config (or (blockstate-def/get-model-cube-texture-config model-name)
                           (when (= model-name "metal_former")
                             (blockstate-def/get-model-cube-texture-config "metal_former_north")))]
    {:kind :cube
     :textures cube-config
     :particle (when (= model-name "metal_former")
                 (:north cube-config))}
    (if-let [texture-config (blockstate-def/get-model-texture-config model-name)]
      {:kind :cube
       :textures {:down (:vert texture-config)
                  :up (:vert texture-config)
                  :north (:side texture-config)
                  :south (:side texture-config)
                  :east (:side texture-config)
                  :west (:side texture-config)}}
      (let [registry-name (model-id->registry-name model-name)
            block-spec (registry-name->block-spec registry-name)
            parent (block-parent-from-spec registry-name)
            explicit-texture (or (get-in block-spec [:rendering :textures :all])
                                 (get-in block-spec [:rendering :texture]))]
        (if (or explicit-texture (not= parent "minecraft:block/cube_all"))
          {:kind :parent
           :parent parent
           :textures (when explicit-texture {:all explicit-texture})}
          {:kind :cube-all
           :texture (block-texture-from-spec registry-name)})))))

(defn block-model-json
  [model-name]
  (let [{:keys [kind textures particle parent texture]} (block-model-spec model-name)]
    (case kind
      :cube (cond-> (bs-support/cube-model-json textures)
              particle (assoc-in [:textures :particle] (bs-support/normalize-texture-id particle)))
      :parent (bs-support/parent-model-json parent textures)
      :cube-all (bs-support/cube-all-model-json texture))))

(defn item-model-json
  [registry-name multipart?]
  (let [block-spec (registry-name->block-spec registry-name)
        flat-item? (true? (get-in block-spec [:rendering :flat-item-icon?]))
        item-model-id (blockstate-def/get-item-model-id modid/*mod-id* registry-name)]
    (if (and flat-item? (not multipart?))
      (bs-support/flat-item-model-json (item-texture-from-spec registry-name))
      (bs-support/parent-model-json item-model-id))))