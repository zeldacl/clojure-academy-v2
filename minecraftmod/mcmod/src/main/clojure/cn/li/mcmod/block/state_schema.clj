(ns cn.li.mcmod.block.state-schema
  "Generic BlockEntity state schema framework.

  A state schema is a vector of FieldSpec maps. Each FieldSpec describes one
  logical field of a block entity's customState map and declares how it should
  be serialised to/from NBT, included in GUI sync payloads, and reflected in
  BlockState properties.

  All NBT access uses the Minecraft 1.20.1 CompoundTag API
  (.contains / .putDouble / .getDouble etc.) via dynamic interop — no static
  imports required — consistent with the rest of mcmod.

  FieldSpec keys (all optional unless marked required):
    :key              keyword   REQUIRED - Clojure state-map key
    :nbt-key          string    REQUIRED for persisted fields - CompoundTag key
    :type             keyword   REQUIRED - one of :double :float :long :int
                                           :boolean :string :keyword
    :default          any       REQUIRED - default value for schema->default-state
    :persist?         boolean   true  = read/written from NBT (default false)
    :gui-sync?        boolean   true  = included in sync payload (default false)
    :block-state-prop string    BlockState property name; nil = not mapped
    :block-state-xf   fn?       (fn [raw-val full-state] -> coerced-value);
                                when nil raw value is used as-is
    :load-fn          fn?       override for load: (fn [tag nbt-key default] -> val)
    :save-fn          fn?       override for save: (fn [state tag nbt-key]   -> nil)"
  (:require [cn.li.mcmod.platform.world :as platform-world])
  (:import [net.minecraft.nbt CompoundTag]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level Level]
           [net.minecraft.core BlockPos]
           [my_mod.block.entity ScriptedBlockEntity]))

;; ============================================================================
;; Internal: 1.20.1 NBT type dispatch
;; ============================================================================

(def ^:private nbt-writers
  {:double  (fn [^CompoundTag tag k v] (.putDouble  tag k (double  v)))
   :float   (fn [^CompoundTag tag k v] (.putFloat   tag k (float   v)))
   :long    (fn [^CompoundTag tag k v] (.putLong    tag k (long    v)))
   :int     (fn [^CompoundTag tag k v] (.putInt     tag k (int     v)))
   :boolean (fn [^CompoundTag tag k v] (.putBoolean tag k (boolean v)))
   :string  (fn [^CompoundTag tag k v] (.putString  tag k (str     v)))
   :keyword (fn [^CompoundTag tag k v] (.putString  tag k (name    v)))})

(def ^:private nbt-readers
  {:double  (fn [^CompoundTag tag k] (.getDouble  tag k))
   :float   (fn [^CompoundTag tag k] (.getFloat   tag k))
   :long    (fn [^CompoundTag tag k] (.getLong    tag k))
   :int     (fn [^CompoundTag tag k] (.getInt     tag k))
   :boolean (fn [^CompoundTag tag k] (.getBoolean tag k))
   :string  (fn [^CompoundTag tag k] (.getString  tag k))
   :keyword (fn [^CompoundTag tag k] (keyword (.getString tag k)))})

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
    (fn [^CompoundTag tag]
      (reduce
        (fn [state spec]
          (let [k    (:key spec)
                dflt (:default spec)]
            (if-not (:persist? spec)
              (assoc state k dflt)
              (let [nk (:nbt-key spec)]
                (if-let [load-fn (:load-fn spec)]
                  (assoc state k (load-fn tag nk dflt))
                  (if (.contains tag nk)
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
    (fn [^ScriptedBlockEntity be ^CompoundTag tag]
      (let [state (or (.getCustomState be) defaults)]
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
      (assoc :pos-x (.getX ^BlockPos pos)
             :pos-y (.getY ^BlockPos pos)
             :pos-z (.getZ ^BlockPos pos))))

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
          (when-let [block (.getBlock ^BlockState blk-state)]
            (let [state-def (.getStateDefinition ^Block block)
                  new-bs    (reduce
                              (fn [bs spec]
                                (let [prop-name (:block-state-prop spec)
                                      raw-val   (get state (:key spec) (:default spec))
                                      val       (if-let [xf (:block-state-xf spec)]
                                                  (xf raw-val state)
                                                  raw-val)
                                      prop      (when state-def
                                                  (.getProperty state-def prop-name))]
                                  (if prop
                                    (.setValue ^BlockState bs prop
                                               (cond
                                                 (integer? val) (Integer/valueOf (int val))
                                                 (boolean? val) (Boolean/valueOf (boolean val))
                                                 :else val))
                                    bs)))
                              blk-state
                              bs-specs)]
              (when (not= new-bs blk-state)
                (.setBlock ^Level level pos new-bs 3)))))
        (catch Exception _)))))
