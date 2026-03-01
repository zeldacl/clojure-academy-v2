(ns my-mod.wireless.world-schema
  "Unified schema for world-data collections.
  
  This namespace defines the schema and macros to generate world-data
  operations (create/destroy, validate, tick, and NBT read/write)."
  (:require [my-mod.nbt.dsl :as nbt-dsl]
            [my-mod.platform.nbt :as nbt]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log]))

(defn- resolve-var-value
  "Resolve symbol to var value, auto-loading namespaces when needed."
  [sym]
  (if-not (symbol? sym)
    sym
    (if-let [v (or (resolve sym) (requiring-resolve sym))]
      (var-get v)
      (throw (ex-info (str "Unable to resolve symbol: " sym)
                      {:symbol sym :ns (str *ns*)})))))

;; =========================================================================
;; NBT Handlers Generator Macro
;; =========================================================================

(defmacro defnbt-handlers-from-schema
  "Generate to-nbt and from-nbt functions from schema's :nbt-fields definition.
  
  Schema should contain:
    :nbt-fields - vector of field specs
    :factory - {:fn factory-name :args [arg1 arg2 ...]}
  
  Field spec: {:name :field :type :type-key :nbt-key \"key\" :atom? false :factory-arg false}"
  [schema-var]
  (let [schema (resolve-var-value schema-var)
        fields (:nbt-fields schema)
        field-locals (into {}
                           (map (fn [field]
                                  (let [n (:name field)]
                                    [n (if (symbol? n) n (symbol (name n)))]))
                                fields))
        factory-fn (get-in schema [:factory :fn])
        factory-args (get-in schema [:factory :args])
        to-nbt-name (symbol (str (name (:collection-name schema)) "-to-nbt"))
        from-nbt-name (symbol (str (name (:collection-name schema)) "-from-nbt"))
        
        ;; to-nbt forms for each field
        to-nbt-forms (mapv (fn [field]
                             (let [name (:name field)
                                   type (:type field)
                                   nbt-key (:nbt-key field)
                                   atom? (:atom? field false)]
                               (case type
                                 :string
                                 `(nbt/nbt-set-string! ~'nbt ~nbt-key (~(if atom? `deref `identity) (~name ~'item)))
                                 
                                 :double
                                 `(nbt/nbt-set-double! ~'nbt ~nbt-key (~(if atom? `deref `identity) (~name ~'item)))
                                 
                                 :vblock
                                 `(nbt/nbt-set-tag! ~'nbt ~nbt-key (~'vb/vblock-to-nbt (~name ~'item)))
                                 
                                 :vblock-list
                                 `(let [~'list-obj (nbt/create-nbt-list)
                                        ~'world (:world (:world-data ~'item))]
                                    (doseq [~'vb-item @(~name ~'item)]
                                      (when (or (not (~'vb/is-chunk-loaded? ~'vb-item ~'world))
                                                (~'vb/vblock-get ~'vb-item ~'world))
                                        (nbt/nbt-append! ~'list-obj (~'vb/vblock-to-nbt ~'vb-item))))
                                    (nbt/nbt-set-tag! ~'nbt ~nbt-key ~'list-obj)))))
                           fields)
        
        ;; from-nbt read forms
        from-nbt-reads (mapv (fn [field]
                               (let [name (:name field)
                       local-sym (get field-locals name)
                                     type (:type field)
                                     nbt-key (:nbt-key field)]
                     [local-sym (case type
                            :string `(nbt/nbt-get-string ~'nbt ~nbt-key)
                            :double `(nbt/nbt-get-double ~'nbt ~nbt-key)
                            :vblock `(~'vb/vblock-from-nbt (nbt/nbt-get-compound ~'nbt ~nbt-key))
                            :vblock-list
                            `(let [~'list-obj (nbt/nbt-get-list ~'nbt ~nbt-key)
                               ~'size (nbt/nbt-list-size ~'list-obj)]
                             (vec (for [~'i (range ~'size)]
                                (~'vb/vblock-from-nbt (nbt/nbt-list-get-compound ~'list-obj ~'i)))))
                            nil)]))
                             fields)
        
        ;; Separate factory args from other fields
        factory-arg-fields (filter :factory-arg fields)
        atom-fields (filter :atom? fields)
        factory-arg-locals (mapv (fn [f] (get field-locals (:name f))) factory-arg-fields)
        
        ;; Build factory call with args
        factory-call (if factory-fn
                       `(~factory-fn ~@factory-args ~@factory-arg-locals)
                       `(throw (Exception. "No factory function defined in schema")))
        
        ;; Restore atom fields
        restore-atoms (mapv (fn [field]
                              (let [name (:name field)
                                    local-sym (get field-locals name)]
                                `(reset! (~name ~'item) ~local-sym)))
                            atom-fields)]
    
    `(do
       (defn ~to-nbt-name
         ~(format "Serialize to NBT - auto-generated from schema")
         [~'item]
         (let [~'nbt (nbt/create-nbt-compound)]
           ~@to-nbt-forms
           ~'nbt))
       
       (defn ~from-nbt-name
         ~(format "Deserialize from NBT - auto-generated from schema")
         [~'world-data ~'nbt]
         (let [~@(apply concat from-nbt-reads)
               ~'item ~factory-call]
           ~@restore-atoms
           ~'item)))))

(defmacro defworld-collection-ops
  "Define create/destroy functions for a world-data collection from a spec map."
  [base-name spec]
  (let [create-name (symbol (str "create-" (name base-name) "!"))
        destroy-name (symbol (str "destroy-" (name base-name) "!"))
        {:keys [create-args create-expr list-atom lookup-atom direct-keys collection-keys key-sources
                unique-keys return-on-unique-fail create-return log-create log-create-key-expr
                log-destroy log-destroy-key-expr log-key-fn log-create-fail]} spec
        create-args (or create-args [])
        direct-keys (or direct-keys [])
        collection-keys (or collection-keys [])
        unique-keys (or unique-keys [])
        key-sources (or key-sources [])
        log-key-fn (or log-key-fn 'identity)
        create-return (or create-return 'item)
        direct-key-forms (mapv (fn [k]
                                 `(when-let [~'value (get ~'item ~k)]
                                    (swap! ~'lookup-atom assoc ~'value ~'item)))
                               direct-keys)
        collection-key-forms (mapv (fn [k]
                                     `(when-let [~'coll (get ~'item ~k)]
                                        (doseq [~'val @~'coll]
                                          (swap! ~'lookup-atom assoc ~'val ~'item))))
                                   collection-keys)
        key-source-forms (mapv (fn [entry]
                                 (let [{:keys [values-expr when-expr]} entry
                                       when-expr (or when-expr true)]
                                   `(when ~when-expr
                                      (doseq [~'val ~values-expr]
                                        (swap! ~'lookup-atom assoc ~'val ~'item)))))
                               key-sources)
        direct-key-remove-forms (mapv (fn [k]
                                        `(when-let [~'value (get ~'item ~k)]
                                           (swap! ~'lookup-atom dissoc ~'value)))
                                      direct-keys)
        collection-key-remove-forms (mapv (fn [k]
                                            `(when-let [~'coll (get ~'item ~k)]
                                               (doseq [~'val @~'coll]
                                                 (swap! ~'lookup-atom dissoc ~'val))))
                                          collection-keys)
        key-source-remove-forms (mapv (fn [entry]
                                        (let [{:keys [values-expr when-expr]} entry
                                              when-expr (or when-expr true)]
                                          `(when ~when-expr
                                             (doseq [~'val ~values-expr]
                                               (swap! ~'lookup-atom dissoc ~'val)))))
                                      key-sources)
        unique-check-forms (mapv (fn [entry]
                                   (let [{:keys [label value-expr value-fn]} entry
                                         value-fn (or value-fn 'identity)
                                         label (or label "key")]
                                     `(let [~'value ~value-expr
                                            ~'formatted (~value-fn ~'value)]
                                        (when (contains? @~'lookup-atom ~'value)
                                          {:label ~label :value ~'formatted}))))
                                 unique-keys)]
    `(do
       (defn ~create-name
         ~(str "Create a new " (name base-name) "\n"
               "Returns the created item")
         [~'world-data ~@create-args]
         (let [~'list-atom (get ~'world-data ~list-atom)
               ~'lookup-atom (get ~'world-data ~lookup-atom)
               ~'conflict ~(if (seq unique-check-forms)
                             `(or ~@unique-check-forms)
                             nil)]
           (if ~'conflict
             (do
               (when ~log-create-fail
                 (~'log/info (format ~log-create-fail (:label ~'conflict) (:value ~'conflict))))
               ~return-on-unique-fail)
             (let [~'item ~create-expr]
               (swap! ~'list-atom conj ~'item)
               ~@direct-key-forms
               ~@collection-key-forms
               ~@key-source-forms
               (when ~log-create
                 (~'log/info (format ~log-create (~log-key-fn ~log-create-key-expr))))
               ~create-return))))
       (defn ~destroy-name
         ~(str "Destroy a " (name base-name))
         [~'world-data ~'item]
         (let [~'list-atom (get ~'world-data ~list-atom)
               ~'lookup-atom (get ~'world-data ~lookup-atom)]
           (reset! (:disposed ~'item) true)
           ~@direct-key-remove-forms
           ~@collection-key-remove-forms
           ~@key-source-remove-forms
           (swap! ~'list-atom (fn [~'items] (filterv #(not= % ~'item) ~'items)))
           (when ~log-destroy
             (~'log/info (format ~log-destroy (~log-key-fn ~log-destroy-key-expr)))))))))

;; =========================================================================
;; Validation Macro
;; =========================================================================

(defmacro defworld-validator
  "Define a validator function for a world-data collection.
  
  Required keys in spec:
    :list-atom   - keyword for the collection atom in world-data
    :vblock-key  - keyword for the vblock on each item
    :destroy-fn  - function to destroy an invalid item
  Optional keys:
    :log-label   - label used in removed log
    :invalid?    - predicate (fn [item world] -> bool) for validity check"
  [fn-name spec]
  (let [{:keys [list-atom vblock-key destroy-fn log-label invalid?]} spec
        log-label (or log-label "items")
        invalid? (or invalid?
                     `(fn [~'item ~'world]
                        (and (not @(:disposed ~'item))
                             (let [~'vblock (~vblock-key ~'item)]
                               (or (not (~'vb/is-chunk-loaded? ~'vblock ~'world))
                                   (some? (~'vb/vblock-get ~'vblock ~'world)))))))]
    `(defn ~fn-name
       ~(str "Validate " log-label ", removing invalid ones\n"
             "Returns number of items removed")
       [~'world-data]
       (let [~'world (:world ~'world-data)
             ~'before (count @(~list-atom ~'world-data))]
         (swap! (~list-atom ~'world-data)
                (fn [~'items]
                  (filterv (fn [~'item]
                             (~invalid? ~'item ~'world))
                           ~'items)))
         (doseq [~'item @(~list-atom ~'world-data)]
           (when @(:disposed ~'item)
             (~destroy-fn ~'world-data ~'item)))
         (let [~'after (count @(~list-atom ~'world-data))
               ~'removed (- ~'before ~'after)]
           (when (> ~'removed 0)
             (~'log/info (format "Removed %d invalid %s" ~'removed ~log-label)))
           ~'removed)))))

;; =========================================================================
;; Collection Definition Macro
;; =========================================================================

(defmacro defcollection
  "Define a complete collection using a schema definition.
  
  Takes a schema var and generates collection operations and validator."
  [schema-var]
  (let [schema (resolve-var-value schema-var)
        impl-base (symbol (str (name (:collection-name schema)) "-impl"))
        create-args (:args (:create schema))
        create-expr (:expr (:create schema))
        list-atom (:atom-name schema)
        lookup-atom (:lookup-atom schema)
        direct-keys (:direct-keys schema)
        collection-keys (:collection-keys schema)
        key-sources (:key-sources schema)
        log-key-fn (:log-key-fn schema)
        create-return (:return (:create schema))
        return-on-unique-fail (:return-on-unique-fail (:create schema))
        unique-keys (:unique (:create schema))
        log-create (:log-create (:create schema))
        log-create-key-expr (:log-create-key-expr (:create schema))
        log-create-fail (:log-create-fail (:create schema))
        log-destroy (:log-destroy (:destroy schema))
        log-destroy-key-expr (:log-destroy-key-expr (:destroy schema))
        vblock-key (:vblock-key (:validator schema))
        log-label (:log-label (:validator schema))]
    `(do
       (defworld-collection-ops ~impl-base
         {:create-args ~create-args
          :create-expr ~create-expr
          :list-atom ~list-atom
          :lookup-atom ~lookup-atom
          :direct-keys ~direct-keys
          :collection-keys ~collection-keys
          :key-sources ~key-sources
          :unique-keys ~unique-keys
          :return-on-unique-fail ~return-on-unique-fail
          :create-return ~create-return
          :log-create ~log-create
          :log-create-key-expr ~log-create-key-expr
          :log-create-fail ~log-create-fail
          :log-destroy ~log-destroy
          :log-destroy-key-expr ~log-destroy-key-expr
          :log-key-fn ~log-key-fn})
       (defworld-validator ~(symbol (str impl-base "-validator"))
         {:list-atom ~list-atom
          :vblock-key ~vblock-key
          :destroy-fn ~(symbol (str "destroy-" (name impl-base) "!"))
          :log-label ~log-label}))))

;; =========================================================================
;; World Schema From Schemas Macro
;; =========================================================================

(defmacro defworld-from-schemas
  "Define world-data functions from schema definitions.
  
  Takes a schema def (with :collections vector) and a create-fn."
  [schema-def create-fn-sym]
  (let [schema (resolve-var-value schema-def)
        schema-vars (:collections schema)
        schemas (mapv (fn [var-sym]
                        (resolve-var-value var-sym))
                      schema-vars)
        nbt-after-read (:nbt-after-read schema)
        tick-name (symbol (name (or (:tick-name schema) 'tick-world-data-impl!)))
        nbt-write-name (symbol (name (or (:nbt-write-name schema) 'world-data-to-nbt-impl)))
        nbt-read-name (symbol (name (or (:nbt-read-name schema) 'world-data-from-nbt-impl)))
        nbt-lists (vec (map (fn [s]
                              (let [nbt (:nbt s)]
                                {:tag (:tag nbt)
                                 :atom (:atom-name s)
                                 :to-nbt (:to-nbt nbt)
                                 :from-nbt (:from-nbt nbt)
                                 :skip? (:skip? nbt)
                                 :rebuild (:rebuild nbt)}))
                            schemas))
        tick-forms (vec (map (fn [s]
                               (let [atom-kw (:atom-name s)
                                     tick-fn (:fn (:tick s))]
                                 `(doseq [~'item @(~atom-kw ~'world-data)]
                                    (when-not @(:disposed ~'item)
                                      (~tick-fn ~'item)))))
                             schemas))]
    `(do
       ~@(map (fn [var-sym] `(defcollection ~var-sym)) schema-vars)
       (nbt-dsl/defworldnbt world-data
         :create ~create-fn-sym
         :write-name ~nbt-write-name
         :read-name ~nbt-read-name
         :lists ~nbt-lists
         :after-read ~nbt-after-read)
       (defn ~tick-name [~'world-data]
         ~@tick-forms)
       (defn ~'tick-world-data! [~'world-data] (~tick-name ~'world-data))
       (defn ~'world-data-to-nbt [~'world-data] (~nbt-write-name ~'world-data))
       (defn ~'world-data-from-nbt [~'world ~'nbt] (~nbt-read-name ~'world ~'nbt)))))

;; =========================================================================
;; Schema Macro
;; =========================================================================

(defmacro defworld-schema
  "Generate world-data functions from a schema map.
  
  Usage:
    (defworld-schema world-data-schema create-world-data)"
  [schema create-fn]
  (let [schema (resolve-var-value schema)
        collections (:collections schema)
        nbt-after-read (:nbt-after-read schema)
      tick-name (symbol (name (or (:tick-name schema) 'tick-world-data-impl!)))
      nbt-write-name (symbol (name (or (:nbt-write-name schema) 'world-data-to-nbt-impl)))
      nbt-read-name (symbol (name (or (:nbt-read-name schema) 'world-data-from-nbt-impl)))
        nbt-lists (vec (keep (fn [c]
                               (when-let [nbt (:nbt c)]
                                 {:tag (:tag nbt)
                                  :atom (:atom nbt)
                                  :to-nbt (:to-nbt nbt)
                                  :from-nbt (:from-nbt nbt)
                                  :skip? (:skip? nbt)
                                  :rebuild (:rebuild nbt)}))
                             collections))
        collection-forms
        (mapcat (fn [c]
                  (let [impl-base (:impl-base c)
                        public (:public c)
                        impl (:impl c)
                        create (:create c)
                        destroy (:destroy c)
                        validator (:validator c)
                        list-atom (:list-atom c)
                        lookup-atom (:lookup-atom c)
                        direct-keys (:direct-keys c)
                        collection-keys (:collection-keys c)
                        key-sources (:key-sources c)
                        log-key-fn (or (:log-key-fn c) 'identity)
                        create-args (:args create)
                        create-expr (:expr create)
                        create-return (:return create)
                        return-on-unique-fail (:return-on-unique-fail create)
                        unique-keys (:unique create)
                        log-create (:log-create create)
                        log-create-key-expr (:log-create-key-expr create)
                        log-create-fail (:log-create-fail create)
                        log-destroy (:log-destroy destroy)
                        log-destroy-key-expr (:log-destroy-key-expr destroy)
                        impl-create (:create impl)
                        impl-destroy (:destroy impl)
                        impl-validate (:validate impl)
                        public-create (:create public)
                        public-destroy (:destroy public)
                        public-validate (:validate public)]
                    (list
                      `(defworld-collection-ops ~impl-base
                         {:create-args ~create-args
                          :create-expr ~create-expr
                          :list-atom ~list-atom
                          :lookup-atom ~lookup-atom
                          :direct-keys ~direct-keys
                          :collection-keys ~collection-keys
                          :key-sources ~key-sources
                          :unique-keys ~unique-keys
                          :return-on-unique-fail ~return-on-unique-fail
                          :create-return ~create-return
                          :log-create ~log-create
                          :log-create-key-expr ~log-create-key-expr
                          :log-create-fail ~log-create-fail
                          :log-destroy ~log-destroy
                          :log-destroy-key-expr ~log-destroy-key-expr
                          :log-key-fn ~log-key-fn})
                      `(defn ~public-create [~'world-data ~@create-args]
                         (~impl-create ~'world-data ~@create-args))
                      `(defn ~public-destroy [~'world-data ~'item]
                         (~impl-destroy ~'world-data ~'item))
                      `(defworld-validator ~impl-validate
                         {:list-atom ~list-atom
                          :vblock-key ~(:vblock-key validator)
                          :destroy-fn ~impl-destroy
                          :log-label ~(:log-label validator)
                          :invalid? ~(:invalid? validator)})
                      `(defn ~public-validate [~'world-data]
                         (~impl-validate ~'world-data)))))
                collections)
        validate-impls (keep (fn [c] (get-in c [:impl :validate])) collections)
        tick-forms (mapv (fn [c]
                           (let [tick-fn (get-in c [:tick :fn])
                                 list-atom (:list-atom c)]
                             `(doseq [~'item @(~list-atom ~'world-data)]
                                (when-not @(:disposed ~'item)
                                  (~tick-fn ~'item)))))
                         collections)]
    `(do
       ~@collection-forms
       (nbt-dsl/defworldnbt world-data
         :create ~create-fn
         :write-name ~nbt-write-name
         :read-name ~nbt-read-name
         :lists ~nbt-lists
         :after-read ~nbt-after-read)
       (defn ~tick-name
         "Tick all world-data collections"
         [~'world-data]
         ~@(map (fn [vfn] `(~vfn ~'world-data)) validate-impls)
         ~@tick-forms)
       (defn ~'tick-world-data!
         "Tick all networks and connections in this world data"
         [~'world-data]
         (~tick-name ~'world-data))
       (defn ~'world-data-to-nbt
         "Serialize world data to NBT"
         [~'world-data]
         (~nbt-write-name ~'world-data))
       (defn ~'world-data-from-nbt
         "Deserialize world data from NBT"
         [~'world ~'nbt]
         (~nbt-read-name ~'world ~'nbt)))))

;; =========================================================================
;; Schema Definition
;; =========================================================================
;; Result: WiWorldData record with these atoms:
;;   - networks (vector) + net-lookup (map: vblock/ssid -> network)
;;   - connections (vector) + node-lookup (map: vblock -> connection)

(def world-data-schema
  '{:collections [my-mod.wireless.network/network-schema
                  my-mod.wireless.node-connection/conn-schema]
    :tick-name tick-world-data-impl!
    :nbt-write-name world-data-to-nbt-impl
    :nbt-read-name world-data-from-nbt-impl
    :nbt-after-read [(~'log/info (format "Loaded %d networks and %d connections from NBT"
                                          (count @(:networks world-data))
                                          (count @(:connections world-data))))]})
