(ns cn.li.mcmod.block.state-schema
  "Generic BlockEntity state schema framework.

  A state schema is a vector of FieldSpec maps. Each FieldSpec describes one
  logical field of a block entity's customState map and declares how it should
  be serialised to/from NBT, included in GUI sync payloads, and reflected in
  BlockState properties.

  This namespace is platform-neutral and must not import `net.minecraft.*`
  types at the top-level. Use platform adapters for any platform-specific
  behaviour."
  (:require [cn.li.mcmod.platform.world :as platform-world]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.be :as pbe]
            [cn.li.mcmod.nbt.dsl :as nbt-dsl]
            [cn.li.mcmod.block.inventory-helpers :as inv-helpers]))

(def ^:private nbt-writers
  "NBT writers extracted from nbt.dsl/type-converters"
  (into {} (map (fn [[k v]] [k (:write v)]) nbt-dsl/type-converters)))

(def ^:private nbt-readers
  "NBT readers extracted from nbt.dsl/type-converters"
  (into {} (map (fn [[k v]] [k (:read v)]) nbt-dsl/type-converters)))

;; ============================================================================
;; Core schema utilities
;; ============================================================================

(defn schema->default-state
  "Build the default state map from each FieldSpec's :default value."
  [schema]
  (into {} (map (fn [spec] [(:key spec) (:default spec)]) schema)))

(defn get-field
  "Return the value of key from state, falling back to the schema default.
  Returns nil when the key is absent from both state and schema."
  [schema state key]
  (if-let [spec (first (filter #(= (:key %) key) schema))]
    (get state key (:default spec))
    (get state key)))

;; ============================================================================
;; NBT load / save
;; ============================================================================

(defn schema->load-fn
  "Return (fn [tag] -> state-map).

  Deserialises a CompoundTag into a fresh state map:
  - Fields with :persist? false receive their :default.
  - Fields with :load-fn delegate to (load-fn tag nbt-key default).
  - Other persisted fields use the internal 1.20.1 NBT readers."
  [schema]
  (let [defaults (schema->default-state schema)]
    (fn [tag]
      (reduce
        (fn [state spec]
          (let [k    (:key spec)
                dflt (:default spec)]
            (if-not (:persist? spec)
              (assoc state k dflt)
              (let [nk (:nbt-key spec)]
                (if-let [load-fn (:load-fn spec)]
                  (assoc state k (load-fn tag nk dflt))
                  (if (nbt/nbt-has-key? tag nk)
                    (if-let [reader (get nbt-readers (:type spec))]
                      (assoc state k (reader tag nk))
                      (assoc state k dflt))
                    (assoc state k dflt)))))))
        defaults
        schema))))

(defn schema->save-fn
  "Return (fn [be tag] -> nil).

  Serialises the BE's customState to a CompoundTag:
  - Only fields with :persist? true are written.
  - Fields with :save-fn delegate to (save-fn state tag nbt-key).
  - Other fields use the internal 1.20.1 NBT writers."
  [schema]
  (let [defaults (schema->default-state schema)]
    (fn [be tag]
      (let [state (or (pbe/get-custom-state be) defaults)]
        (doseq [spec schema]
          (when (:persist? spec)
            (let [k  (:key spec)
                  nk (:nbt-key spec)
                  v  (get state k (:default spec))]
              (if-let [save-fn (:save-fn spec)]
                (save-fn state tag nk)
                (when-let [writer (get nbt-writers (:type spec))]
                  (writer tag nk v))))))))))

;; ============================================================================
;; GUI sync payload
;; ============================================================================

(defn schema->sync-payload
  "Build a GUI sync payload map.

  Includes all fields where :gui-sync? is true, plus :pos-x/:pos-y/:pos-z
  derived from pos (a BlockPos-like object with .getX/.getY/.getZ)."
  [schema state pos]
  (-> (reduce
        (fn [m spec]
          (if (:gui-sync? spec)
            (assoc m (:key spec) (get state (:key spec) (:default spec)))
            m))
        {}
        schema)
            (assoc :pos-x (pos/pos-x pos)
              :pos-y (pos/pos-y pos)
              :pos-z (pos/pos-z pos))))

;; ============================================================================
;; BlockState updater (DEPRECATED - use build-block-state-updater for nested format)
;; ============================================================================

(defn schema->block-state-updater
  "DEPRECATED: Use build-block-state-updater for nested :block-state format.

  Return (fn [state level pos] -> nil).

  Iterates over all FieldSpecs with :block-state-prop set and attempts to
  update the in-world BlockState.  When :block-state-xf is supplied it is
  called as (xf raw-value full-state) to get the coerced BlockState value.
  Failures are silently swallowed so a missing property never crashes a tick."
  [schema]
  (let [bs-specs (filterv :block-state-prop schema)]
    (fn [state level pos]
      (try
        (when-let [blk-state (platform-world/world-get-block-state level pos)]
              (let [state-def (platform-world/block-state-get-state-definition blk-state)
                new-bs    (reduce
                           (fn [bs spec]
                             (let [prop-name (:block-state-prop spec)
                                   raw-val   (get state (:key spec) (:default spec))
                                   val       (if-let [xf (:block-state-xf spec)]
                                               (xf raw-val state)
                                               raw-val)
                                      prop      (when state-def
                                                  (platform-world/block-state-get-property blk-state state-def prop-name))]
                               (if prop
                                 (platform-world/block-state-set-property bs prop val)
                                 bs)))
                           blk-state
                           bs-specs)]
            (when (not= new-bs blk-state)
              (platform-world/world-set-block level pos new-bs 3))))
        (catch Exception _)))))

;; ============================================================================
;; Schema Organization Utilities
;; ============================================================================

(defn merge-field-definitions
  "Merge field definitions by :key, combining metadata from all groups.

  Enables organizing schema into conceptual layers (NBT, BlockState, GUI, etc.)
  where the same field can appear in multiple groups with different metadata.

  Example:
    (merge-field-definitions
      [[{:key :energy :persist? true :default 0.0}]
       [{:key :energy :block-state {:prop \"energy\" :type :integer}}]])
    => [{:key :energy :persist? true :default 0.0
         :block-state {:prop \"energy\" :type :integer}}]"
  [field-groups]
  (let [all-fields (apply concat field-groups)
        by-key (group-by :key all-fields)]
    (vec
      (for [[k fields] by-key]
        (apply merge fields)))))

;; ============================================================================
;; BlockState Utilities (Nested Format)
;; ============================================================================

(defn extract-block-state-properties
  "Extract BlockState properties from nested :block-state format.

  Input: vector of field definitions with :block-state {:prop ... :type ...}
  Output: map of {property-keyword {:name :type :min :max :default}}

  Used for defblock :block-state-properties registration."
  [blockstate-fields]
  (into {}
    (for [field blockstate-fields
          :when (:block-state field)
          :let [bs (:block-state field)
                prop-name (:prop bs)]]
      [(keyword prop-name)
       {:name prop-name
        :type (:type bs)
        :min (:min bs)
        :max (:max bs)
        :default (:default bs)}])))

(defn build-block-state-updater
  "Generate BlockState updater function from nested :block-state format.

  Returns: (fn [state level pos] -> updates BlockState in world)

  Handles :xf transform function (can be symbol or fn).
  Replaces old schema->block-state-updater (flat format)."
  [blockstate-fields]
  (let [bs-specs (filterv :block-state blockstate-fields)]
    (fn [state level pos]
      (try
        (when-let [blk-state (platform-world/world-get-block-state level pos)]
          (let [state-def (platform-world/block-state-get-state-definition blk-state)
                new-bs    (reduce
                           (fn [bs spec]
                             (let [prop-name (:prop (:block-state spec))
                                   raw-val   (get state (:key spec) (:default spec))
                                   xf-fn     (:xf (:block-state spec))
                                   val       (if xf-fn
                                               (let [xf (if (symbol? xf-fn)
                                                         (requiring-resolve xf-fn)
                                                         xf-fn)]
                                                 (xf raw-val state))
                                               raw-val)
                                   prop      (when state-def
                                               (platform-world/block-state-get-property blk-state state-def prop-name))]
                               (if prop
                                 (platform-world/block-state-set-property bs prop val)
                                 bs)))
                           blk-state
                           bs-specs)]
            (when (not= new-bs blk-state)
              (platform-world/world-set-block level pos new-bs 3))))
        (catch Exception _)))))

;; ============================================================================
;; Network Handler Generation
;; ============================================================================

(defn build-network-handlers
  "Generate network message handlers from :network-editable? fields.

  Returns: map of {msg-keyword handler-fn}

  Each handler receives [payload player] and updates the BE field.
  Requires net-helpers namespace for get-world and get-tile-at."
  [network-editable-fields]
  (into {}
    (for [field network-editable-fields
          :when (:network-editable? field)
          :let [msg-key (:network-msg field)
                field-key (:key field)
                payload-key (:gui-payload-key field field-key)]]
      [msg-key
       (fn [payload player]
         (let [get-world (requiring-resolve 'cn.li.ac.wireless.gui.network-handler-helpers/get-world)
               get-tile-at (requiring-resolve 'cn.li.ac.wireless.gui.network-handler-helpers/get-tile-at)
               world (get-world player)
               tile (get-tile-at world payload)
               new-value (get payload payload-key)]
           (if (and tile new-value)
             (do
               (inv-helpers/update-be-field! tile field-key new-value)
               {:success true})
             {:success false})))])))


;; ============================================================================
;; Schema filtering utilities
;; ============================================================================

(defn filter-server-fields
  "Filter schema to only server-side fields (persist? true or no gui-only? flag).

  Used to extract fields that should be handled by server-side code (NBT persistence,
  BlockState updates, etc.). Excludes client-only GUI fields."
  [schema]
  (filterv #(and (not (:gui-only? %))
                 (or (:persist? %) (:block-state %)))
           schema))

(defn filter-gui-fields
  "Filter schema to only GUI-relevant fields (gui-sync? true or gui-only? true).

  Used to extract fields that should be present in GUI containers (client-side atoms)."
  [schema]
  (filterv #(or (:gui-sync? %) (:gui-only? %))
           schema))

(defn filter-by-tag
  "Filter schema to fields that have a specific tag key set to a truthy value.

  Example: (filter-by-tag schema :network-editable?) returns only network-editable fields."
  [schema tag-key]
  (filterv #(get % tag-key) schema))
