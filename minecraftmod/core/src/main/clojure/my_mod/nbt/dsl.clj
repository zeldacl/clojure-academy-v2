(ns my-mod.nbt.dsl
  "NBT DSL - Declarative NBT serialization using Clojure macros
  
  Provides a clean, declarative way to define NBT read/write operations
  for tile entities and other game objects."
  (:require [my-mod.util.log :as log]
            [my-mod.inventory.core :as inv]))

;; ============================================================================
;; Type Converters
;; ============================================================================

(def type-converters
  "Map of field types to their read/write converters"
  {:double
   {:write (fn [nbt key value]
             (.setDouble nbt key value))
    :read (fn [nbt key]
            (.getDouble nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :string
   {:write (fn [nbt key value]
             (.setString nbt key value))
    :read (fn [nbt key]
            (.getString nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :int
   {:write (fn [nbt key value]
             (.setInteger nbt key value))
    :read (fn [nbt key]
            (.getInteger nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :boolean
   {:write (fn [nbt key value]
             (.setBoolean nbt key value))
    :read (fn [nbt key]
            (.getBoolean nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :float
   {:write (fn [nbt key value]
             (.setFloat nbt key value))
    :read (fn [nbt key]
            (.getFloat nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :long
   {:write (fn [nbt key value]
             (.setLong nbt key value))
    :read (fn [nbt key]
            (.getLong nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :keyword
   {:write (fn [nbt key value]
             (.setString nbt key (name value)))
    :read (fn [nbt key]
            (keyword (.getString nbt key)))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}
   
   :atom
   {:write (fn [nbt key value]
             ;; Atoms need to be dereferenced before writing
             ;; The actual type is handled by a nested converter
             (throw (ex-info "Atom type needs explicit sub-type" {:key key})))
    :read (fn [nbt key]
            (throw (ex-info "Atom type needs explicit sub-type" {:key key})))}
   
   :inventory
   {:write (fn [nbt key value]
             (inv/write-inventory-to-nbt value nbt key))
    :read (fn [nbt key]
            (inv/read-inventory-from-nbt value nbt key))
    :has-key? (fn [nbt key]
                (.hasKey nbt key))}})

;; ============================================================================
;; Field Accessor Helpers
;; ============================================================================

(defn get-field-value
  "Get value from tile entity field, handling atoms and direct values"
  [tile field-path atom?]
  (let [value (get-in tile field-path)]
    (if atom?
      @value
      value)))

(defn set-field-value!
  "Set value in tile entity field, handling atoms and direct values"
  [tile field-path value atom?]
  (if atom?
    (reset! (get-in tile field-path) value)
    (assoc-in tile field-path value)))

;; ============================================================================
;; NBT Field Specification
;; ============================================================================

(defrecord NbtFieldSpec
  [field-key       ; Keyword - Clojure field name in record (e.g., :energy)
   nbt-key         ; String - NBT key name (e.g., "energy")
   type            ; Keyword - field type (:double, :string, :int, etc.)
   atom?           ; Boolean - is field an atom?
   default         ; Any - default value if not present in NBT
   getter          ; Function - getter function (tile) -> value (optional)
   setter          ; Function - setter function (tile value) -> tile (optional)
   custom-write    ; Function - custom write function (optional)
   custom-read     ; Function - custom read function (optional)
   skip-on-write?  ; Function - predicate to skip writing (optional)
   transform-write ; Function - transform value before writing (optional)
   transform-read  ; Function - transform value after reading (optional)
   ])

;; ============================================================================
;; Core DSL Functions
;; ============================================================================

(defn write-nbt-field
  "Write a single field to NBT according to its specification"
  [tile nbt field-spec]
  (let [{:keys [field-key nbt-key type atom? getter custom-write skip-on-write? transform-write]} field-spec]
    (when-not (and skip-on-write? (skip-on-write? tile))
      (let [value (if getter
                    (getter tile)
                    (get-field-value tile [field-key] atom?))
            value (if transform-write (transform-write value) value)]
        (if custom-write
          (custom-write tile nbt nbt-key value)
          (when-let [converter (get type-converters type)]
            ((:write converter) nbt nbt-key value)))))))

(defn read-nbt-field
  "Read a single field from NBT according to its specification"
  [tile nbt field-spec]
  (let [{:keys [field-key nbt-key type atom? setter custom-read default transform-read]} field-spec
        has-key? (if-let [converter (get type-converters type)]
                   ((:has-key? converter) nbt nbt-key)
                   (.hasKey nbt nbt-key))]
    (when has-key?
      (let [value (if custom-read
                    (custom-read tile nbt nbt-key)
                    (when-let [converter (get type-converters type)]
                      ((:read converter) nbt nbt-key)))
            value (if transform-read (transform-read value) value)
            value (or value default)]
        (if setter
          (setter tile value)
          (set-field-value! tile [field-key] value atom?))))))

(defn write-nbt-fields
  "Write all fields to NBT according to specifications"
  [tile nbt field-specs]
  (doseq [field-spec field-specs]
    (write-nbt-field tile nbt field-spec))
  nbt)

(defn read-nbt-fields
  "Read all fields from NBT according to specifications"
  [tile nbt field-specs]
  (doseq [field-spec field-specs]
    (read-nbt-field tile nbt field-spec))
  tile)

;; ============================================================================
;; Macro: defnbt
;; ============================================================================

(defmacro defnbt
  "Define NBT serialization for a tile entity or record.
  
  Generates two functions:
  - write-{name}-to-nbt [tile nbt] -> nbt
  - read-{name}-from-nbt [tile nbt] -> tile
  
  Usage:
    (defnbt node
      [:energy \"energy\" :double]
      [:node-name \"nodeName\" :string]
      [:password \"password\" :string]
      [:placer-name \"placer\" :string]
      [:inventory \"inventory\" :inventory])
  
  Field specification format:
    [field-key nbt-key type & options]
  
  Options:
    :atom? true         - Field is an atom (default: false)
    :default value      - Default value if not in NBT
    :getter fn          - Getter function (tile) -> value
    :setter fn          - Setter function (tile value) -> tile
    :custom-write fn    - Custom write function (tile nbt key value) -> void
    :custom-read fn     - Custom read function (tile nbt key) -> value
    :skip-on-write? fn  - Predicate (tile) -> bool, skip if true
    :transform-write fn - Transform value before writing
    :transform-read fn  - Transform value after reading
  
  Advanced usage with options:
    (defnbt matrix
      [:placer-name \"placer\" :string]
      [:plate-count \"plateCount\" :int :atom? true]
      [:sub-id \"subId\" :int]
      [:direction \"direction\" :keyword]
      [:inventory \"inventory\" :inventory])"
  [name & field-specs]
  (let [name-str (clojure.core/name name)
        write-fn-name (symbol (str "write-" name-str "-to-nbt"))
        read-fn-name (symbol (str "read-" name-str "-from-nbt"))
        
        ;; Parse field specifications
        parsed-specs (mapv (fn [spec]
                             (let [[field-key nbt-key type & options] spec
                                   options-map (apply hash-map options)]
                               (map->NbtFieldSpec
                                 {:field-key field-key
                                  :nbt-key nbt-key
                                  :type type
                                  :atom? (get options-map :atom? false)
                                  :default (get options-map :default)
                                  :getter (get options-map :getter)
                                  :setter (get options-map :setter)
                                  :custom-write (get options-map :custom-write)
                                  :custom-read (get options-map :custom-read)
                                  :skip-on-write? (get options-map :skip-on-write?)
                                  :transform-write (get options-map :transform-write)
                                  :transform-read (get options-map :transform-read)})))
                           field-specs)]
    
    `(do
       ;; Generate write function
       (defn ~write-fn-name
         ~(str "Save " name-str " to NBT\n\n"
               "Auto-generated by defnbt macro.\n\n"
               "Saves:\n"
               (clojure.string/join "\n" 
                 (map #(str "- " (clojure.core/name (:field-key %))) parsed-specs)))
         [~'tile ~'nbt]
         (write-nbt-fields ~'tile ~'nbt ~parsed-specs)
         ~'nbt)
       
       ;; Generate read function
       (defn ~read-fn-name
         ~(str "Load " name-str " from NBT\n\n"
               "Auto-generated by defnbt macro.\n\n"
               "Restores:\n"
               (clojure.string/join "\n"
                 (map #(str "- " (clojure.core/name (:field-key %))) parsed-specs)))
         [~'tile ~'nbt]
         (read-nbt-fields ~'tile ~'nbt ~parsed-specs)
         ~'tile)
       
       ;; Log registration
       (log/info ~(str "Registered NBT serialization for: " name-str)))))

;; ============================================================================
;; Convenience Macros for Common Patterns
;; ============================================================================

(defmacro defnbt-simple
  "Simplified defnbt for common case: all non-atom fields
  
  Usage:
    (defnbt-simple player
      :name \"playerName\" :string
      :health \"health\" :double
      :level \"level\" :int)"
  [name & field-specs]
  (let [grouped (partition 3 field-specs)
        expanded (mapv (fn [[field-key nbt-key type]]
                         [field-key nbt-key type])
                       grouped)]
    `(defnbt ~name ~@expanded)))

;; ============================================================================
;; Helper Functions for Complex Types
;; ============================================================================

(defn write-atom-int
  "Write an atom containing an integer"
  [tile nbt nbt-key atom-value]
  (.setInteger nbt nbt-key @atom-value))

(defn read-atom-int
  "Read an integer into an atom"
  [tile nbt nbt-key]
  (when (.hasKey nbt nbt-key)
    (reset! (get-in tile [nbt-key]) (.getInteger nbt nbt-key))))

(defn write-atom-double
  "Write an atom containing a double"
  [tile nbt nbt-key atom-value]
  (.setDouble nbt nbt-key @atom-value))

(defn read-atom-double
  "Read a double into an atom"
  [tile nbt nbt-key]
  (when (.hasKey nbt nbt-key)
    (reset! (get-in tile [nbt-key]) (.getDouble nbt nbt-key))))

(defn write-atom-boolean
  "Write an atom containing a boolean"
  [tile nbt nbt-key atom-value]
  (.setBoolean nbt nbt-key @atom-value))

(defn read-atom-boolean
  "Read a boolean into an atom"
  [tile nbt nbt-key]
  (when (.hasKey nbt nbt-key)
    (reset! (get-in tile [nbt-key]) (.getBoolean nbt nbt-key))))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-nbt-field-spec
  "Validate NBT field specification"
  [spec]
  (when-not (:field-key spec)
    (throw (ex-info "NBT field spec must have :field-key" {:spec spec})))
  (when-not (:nbt-key spec)
    (throw (ex-info "NBT field spec must have :nbt-key" {:spec spec})))
  (when-not (:type spec)
    (throw (ex-info "NBT field spec must have :type" {:spec spec})))
  (when-not (get type-converters (:type spec))
    (when-not (or (:custom-write spec) (:custom-read spec))
      (throw (ex-info "Unknown NBT type and no custom converters"
                      {:type (:type spec)
                       :available-types (keys type-converters)}))))
  true)
