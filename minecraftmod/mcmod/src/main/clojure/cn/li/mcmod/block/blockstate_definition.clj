(ns cn.li.mcmod.block.blockstate-definition
  "BlockState datagen business logic (platform independent).

   This module replaces the old `ac.block.blockstate-definition` so Forge
   datagen can avoid any dependency on `ac` namespaces.

   It derives all needed information from `cn.li.mcmod.registry.metadata`,
   which is populated by the DSL namespaces at runtime."
  (:require [clojure.string :as str]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.config :as config]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord BlockStatePart
  [condition
   models])

(defrecord BlockStateDefinition
  [registry-name
   properties
   parts])

;; ============================================================================
;; Node model naming conventions
;; ============================================================================

(defn- parse-node-model-name
  "Parse node model names like:
   - node_basic_base
   - node_basic_connected
   - node_basic_energy_3

   Returns [node-type variant] or nil.
   node-type is the part after `node_` and before the final `_`."
  [model-name]
  (when-let [[_ node-type variant]
             (re-matches #"node_(.+)_(base|connected|energy_\d+)" model-name)]
    [node-type variant]))

(defn- node-block-registry-name
  [node-type]
  (str "node_" node-type))

;; Cache node-type -> [min max]
(defonce ^:private node-energy-range-cache (atom {}))

(defn- node-energy-range
  "Return [min max] for the given node-type by looking up the corresponding
   node block spec from registry metadata."
  [node-type]
  (let [k (str node-type)]
    (if-let [v (get @node-energy-range-cache k)]
      v
      (let [node-registry-name (node-block-registry-name k)
            block-id (some (fn [bid]
                              (when (= (registry-metadata/get-block-registry-name bid)
                                       node-registry-name)
                                bid))
                            (registry-metadata/get-all-block-ids))
            props (when block-id (registry-metadata/get-block-state-properties block-id))
            energy-prop (when props (:energy props))
            min-val (int (or (get energy-prop :min) 0))
            max-val (int (or (get energy-prop :max) 4))
            range [min-val max-val]]
        (swap! node-energy-range-cache assoc k range)
        range))))

(defn- variant->energy-level
  "Map a node model variant to energy level used in texture naming.
   base/connected => 0
   energy_N => N clamped to the node's min/max range."
  [node-type variant]
  (let [[min-v max-v] (node-energy-range node-type)
        raw (cond
              (#{"base" "connected"} variant) 0
              (str/starts-with? variant "energy_") (Integer/parseInt (subs variant (count "energy_")))
              :else 0)]
    (max min-v (min max-v raw))))

(defn get-model-texture-config
  "Return texture config for a node model name (no namespace), e.g.:
   - node_basic_base
   - node_basic_connected
   - node_basic_energy_3

   Returns {:side \"<modid>:block/node_<type>_side_<level>\"
             :vert \"<modid>:block/node_top_<0|1>\"}
   or nil if the model-name is not a node model."
  [model-name]
  (when-let [[node-type variant] (parse-node-model-name model-name)]
    (let [level (variant->energy-level node-type variant)
          top-texture (if (= variant "connected")
                        (str config/*mod-id* ":block/node_top_1")
                        (str config/*mod-id* ":block/node_top_0"))
          side-texture (str config/*mod-id*
                             ":block/node_" node-type "_side_" level)]
      {:side side-texture
       :vert top-texture})))

;; ============================================================================
;; Definitions generation (datagen)
;; ============================================================================

(defn- is-node-block-registry-name?
  [registry-name]
  (str/starts-with? (str registry-name) "node_"))

(defn- get-node-block-ids
  "Return all block-ids whose registry-name looks like `node_*`."
  []
  (filter (fn [bid]
            (is-node-block-registry-name? (registry-metadata/get-block-registry-name bid)))
          (registry-metadata/get-all-block-ids)))

(defn get-all-definitions
  "Return map: block-id(keyword) -> BlockStateDefinition."
  []
  (let [all-block-ids (registry-metadata/get-all-block-ids)
        node-block-ids (set (get-node-block-ids))
        simple-block-ids (remove node-block-ids all-block-ids)]
    (merge
     ;; Simple blocks: single unconditional model (honor registry-name).
     (into {}
           (for [block-id simple-block-ids
                 :let [registry-name (registry-metadata/get-block-registry-name block-id)]]
             [(keyword block-id)
              (BlockStateDefinition.
               registry-name
               {}
               [(->BlockStatePart nil
                                   [(str config/*mod-id* ":block/" registry-name)])])]))
     ;; Node blocks: multipart energy + connected + base.
     (into {}
           (for [block-id node-block-ids
                 :let [registry-name (registry-metadata/get-block-registry-name block-id)
                       props (registry-metadata/get-block-state-properties block-id)
                       energy-min (int (or (get-in props [:energy :min]) 0))
                       energy-max (int (or (get-in props [:energy :max]) 4))
                       connected-type (get-in props [:connected :type])]]
             [(keyword block-id)
              (let [base-model (str config/*mod-id* ":block/" registry-name "_base")
                    energy-models (vec (for [level (range energy-min (inc energy-max))]
                                          (BlockStatePart.
                                           {:energy (str level)}
                                           [(str config/*mod-id* ":block/" registry-name "_energy_" level)])))
                    connected-model (BlockStatePart.
                                      {:connected "true"}
                                      [(str config/*mod-id* ":block/" registry-name "_connected")])]
                (BlockStateDefinition.
                 registry-name
                 {:energy {:min energy-min :max energy-max}
                  :connected {:type connected-type}}
                 (vec (concat [(BlockStatePart. nil [base-model])]
                              energy-models
                              [connected-model]))))])))))

(defn get-block-state-definition
  [block-key]
  (or (some-> (get-all-definitions) (get block-key))
      (some-> (get-all-definitions) (get (keyword (name block-key))))))

(defn is-multipart-block?
  "Multipart when parts count > 1."
  [definition]
  (> (count (:parts definition)) 1))

(defn is-node-block?
  "Node blocks are those with registry-name `node_*`."
  [registry-name]
  (boolean (re-find #"^node_" (str registry-name))))

(defn get-item-model-id
  "Item model ids:
   - node blocks: `<modid>:block/<registry>_base`
   - others:      `<modid>:block/<registry>`"
  [mod-id registry-name]
  (if (is-node-block? registry-name)
    (str mod-id ":block/" registry-name "_base")
    (str mod-id ":block/" registry-name)))

;; End of file

