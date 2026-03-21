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
            [cn.li.mcmod.platform.be :as pbe]))

(def ^:private nbt-writers
  {:double  (fn [tag k v] (nbt/nbt-set-double! tag k v))
   :float   (fn [tag k v] (nbt/nbt-set-float!  tag k v))
   :long    (fn [tag k v] (nbt/nbt-set-long!   tag k v))
   :int     (fn [tag k v] (nbt/nbt-set-int!    tag k v))
   :boolean (fn [tag k v] (nbt/nbt-set-boolean! tag k v))
   :string  (fn [tag k v] (nbt/nbt-set-string!  tag k (str v)))
   :keyword (fn [tag k v] (nbt/nbt-set-string!  tag k (name v)))})

(def ^:private nbt-readers
  {:double  (fn [tag k] (nbt/nbt-get-double tag k))
   :float   (fn [tag k] (nbt/nbt-get-float  tag k))
   :long    (fn [tag k] (nbt/nbt-get-long   tag k))
   :int     (fn [tag k] (nbt/nbt-get-int    tag k))
   :boolean (fn [tag k] (nbt/nbt-get-boolean tag k))
   :string  (fn [tag k] (nbt/nbt-get-string  tag k))
   :keyword (fn [tag k] (keyword (nbt/nbt-get-string tag k)))})

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
;; BlockState updater
;; ============================================================================

(defn schema->block-state-updater
  "Return (fn [state level pos] -> nil).

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
