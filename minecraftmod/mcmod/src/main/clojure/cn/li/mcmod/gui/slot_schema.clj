(ns cn.li.mcmod.gui.slot-schema
  "Slot schema DSL and query API.

  A slot schema is the single source of truth for a GUI's tile slots.
  Slot indexes, ranges, and layout metadata are derived from declaration order."
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^{:doc "Registered slot schemas by schema-id keyword."}
  slot-schema-registry
  (atom {}))

(defn- ensure-keyword
  [value label]
  (when-not (keyword? value)
    (throw (ex-info (str label " must be a keyword") {label value})))
  value)

(defn- ensure-int
  [value label]
  (when-not (integer? value)
    (throw (ex-info (str label " must be an integer") {label value})))
  value)

(defn- normalize-slot
  [idx slot]
  (let [{:keys [id type x y]} slot]
    {:id (ensure-keyword id ":slot/id")
     :type (ensure-keyword type ":slot/type")
     :x (ensure-int x ":slot/x")
     :y (ensure-int y ":slot/y")
     :index idx}))

(defn- derived-ranges
  [tile-slot-count]
  {:tile [0 (dec tile-slot-count)]
   :player-main [tile-slot-count (+ tile-slot-count 26)]
   :player-hotbar [(+ tile-slot-count 27) (+ tile-slot-count 35)]})

(defn- validate-slots!
  [schema-id slots]
  (when-not (seq slots)
    (throw (ex-info "Slot schema requires at least one slot"
                    {:schema-id schema-id})))
  (let [ids (map :id slots)
        duplicate-ids (->> ids frequencies (filter (fn [[_id n]] (> n 1))) (map first) seq)]
    (when duplicate-ids
      (throw (ex-info "Slot schema has duplicate slot ids"
                      {:schema-id schema-id
                       :duplicate-ids duplicate-ids}))))
  (let [coords (map (juxt :x :y) slots)
        duplicate-coords (->> coords frequencies (filter (fn [[_xy n]] (> n 1))) (map first) seq)]
    (when duplicate-coords
      (throw (ex-info "Slot schema has duplicate slot coordinates"
                      {:schema-id schema-id
                       :duplicate-coordinates duplicate-coords})))))

(defn register-slot-schema!
  "Register a slot schema.

  Config keys:
  - :schema-id keyword
  - :slots [{:id :core :type :core :x 47 :y 24} ...]

  Returns normalized schema with derived fields."
  [{:keys [schema-id slots]}]
  (let [schema-id (ensure-keyword schema-id ":schema-id")
        slots (mapv normalize-slot (range) slots)]
    (validate-slots! schema-id slots)
    (let [tile-slot-count (count slots)
          slot-layout {:slots (mapv (fn [{:keys [type index x y]}]
                                      {:type type :index index :x x :y y})
                                    slots)
                       :ranges (derived-ranges tile-slot-count)}
          schema {:schema-id schema-id
                  :slots slots
                  :slot-layout slot-layout
                  :tile-slot-count tile-slot-count
                  :slot-id->index (into {} (map (juxt :id :index) slots))
                  :slot-index->slot (into {} (map (juxt :index identity) slots))
                  :slot-type->indexes (into {}
                                            (for [[slot-type grouped] (group-by :type slots)]
                                              [slot-type (mapv :index grouped)]))}]
      (swap! slot-schema-registry assoc schema-id schema)
      (log/info "Registered slot schema:" schema-id)
      schema)))

(defmacro defslot-schema
  "Define and register a slot schema.

  Example:
  (defslot-schema wireless-matrix
    :schema-id :wireless-matrix
    :slots [{:id :plate-a :type :plate :x 0 :y 0}])"
  [schema-name & options]
  (let [options-map (apply hash-map options)
        schema-id (or (:schema-id options-map) (keyword (name schema-name)))]
    `(def ~schema-name
       (register-slot-schema! ~(assoc options-map :schema-id schema-id)))))

(defn get-slot-schema
  [schema-id]
  (get @slot-schema-registry schema-id))

(defn get-slot-layout
  [schema-id]
  (some-> (get-slot-schema schema-id) :slot-layout))

(defn tile-slot-count
  [schema-id]
  (or (some-> (get-slot-schema schema-id) :tile-slot-count) 0))

(defn slot-index
  [schema-id slot-id]
  (get-in (get-slot-schema schema-id) [:slot-id->index slot-id]))

(defn slot-id
  [schema-id slot-index]
  (get-in (get-slot-schema schema-id) [:slot-index->slot slot-index :id]))

(defn slot-type
  [schema-id slot-index]
  (get-in (get-slot-schema schema-id) [:slot-index->slot slot-index :type]))

(defn slot-type?
  [schema-id slot-index expected-type]
  (= (slot-type schema-id slot-index) expected-type))

(defn slot-indexes-by-type
  [schema-id slot-type]
  (get-in (get-slot-schema schema-id) [:slot-type->indexes slot-type] []))

(defn all-slot-indexes
  [schema-id]
  (->> (get-slot-schema schema-id)
       :slots
       (mapv :index)))

(defn slot-indexes
  [schema-id slot-ids]
  (->> slot-ids
       (mapv (fn [slot-id']
               (let [idx (slot-index schema-id slot-id')]
                 (when-not (some? idx)
                   (throw (ex-info "Unknown slot id in slot-indexes"
                                   {:schema-id schema-id
                                    :slot-id slot-id'})))
                 idx)))))

(defn get-slot-range
  [schema-id section]
  (get-in (get-slot-layout schema-id) [:ranges section] [0 0]))

(defn build-quick-move-config
  "Compile semantic quick-move rules to index-based config.

  Input config:
  - :inventory-pred (fn [slot-index player-inventory-start])
  - :rules [{:accept? fn :slot-ids [:core]}
            {:accept? fn :slot-type :plate}]"
  [schema-id {:keys [inventory-pred rules]}]
  {:container-slots (set (all-slot-indexes schema-id))
   :inventory-pred inventory-pred
   :rules (mapv (fn [{:keys [accept? slot-ids slot-type]}]
                  {:accept? accept?
                   :slots (cond
                            slot-ids (slot-indexes schema-id slot-ids)
                            slot-type (slot-indexes-by-type schema-id slot-type)
                            :else [])})
                rules)})
