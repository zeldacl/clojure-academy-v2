(ns my-mod.wireless.world-schema
  "Unified schema for world-data collections.
  
  This namespace defines the schema and macros to generate world-data
  operations (create/destroy, validate, tick, and NBT read/write)."
  (:require [my-mod.nbt.dsl :as nbt]))

;; =========================================================================
;; Collection Ops Macro
;; =========================================================================

(defmacro defworld-collection-ops
  "Define create/destroy functions for a world-data collection using a spec.
  
  Required keys in spec:
    :create-args    - vector of symbols for create fn args (excluding world-data)
    :create-expr    - form that creates the item
    :list-atom      - keyword for the collection atom in world-data
    :lookup-atom    - keyword for the lookup atom in world-data
  Optional keys:
    :direct-keys     - vector of keywords for direct lookup keys on the item
    :collection-keys - vector of keywords for collection fields on the item
    :key-sources     - vector of maps {:values-expr <form> :when-expr <form>}
    :unique-keys    - vector of maps {:label "..." :value-expr <form> :value-fn <fn>}
    :return-on-unique-fail - return value when uniqueness check fails
    :create-return  - value to return on successful create (default: item)
    :log-create     - format string for create log
    :log-create-key-expr - form for create log key
    :log-destroy    - format string for destroy log
    :log-destroy-key-expr - form for destroy log key
    :log-key-fn     - function to format keys in logs
    :log-create-fail - format string for uniqueness failure log"
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
;; Schema Macro
;; =========================================================================

(defmacro defworld-schema
  "Generate world-data functions from a schema map.
  
  Usage:
    (defworld-schema world-data-schema create-world-data)"
  [schema create-fn]
  (let [schema (if (symbol? schema) (var-get (resolve schema)) schema)
        collections (:collections schema)
        nbt-after-read (:nbt-after-read schema)
        tick-name (or (:tick-name schema) 'tick-world-data-impl!)
        nbt-write-name (or (:nbt-write-name schema) 'world-data-to-nbt-impl)
        nbt-read-name (or (:nbt-read-name schema) 'world-data-from-nbt-impl)
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
       (nbt/defworldnbt world-data
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
       (defn tick-world-data!
         "Tick all networks and connections in this world data"
         [~'world-data]
         (~tick-name ~'world-data))
       (defn world-data-to-nbt
         "Serialize world data to NBT"
         [~'world-data]
         (~nbt-write-name ~'world-data))
       (defn world-data-from-nbt
         "Deserialize world data from NBT"
         [~'world ~'nbt]
         (~nbt-read-name ~'world ~'nbt)))))

;; =========================================================================
;; Schema Definition
;; =========================================================================

(def world-data-schema
  '{:collections
    [{:name network
      :impl-base network-impl
      :public {:create create-network!
         :destroy destroy-network!
         :validate validate-networks!}
      :impl {:create create-network-impl!
       :destroy destroy-network-impl!
       :validate validate-networks-impl!}
      :create {:args [matrix-vblock ssid password]
         :expr (my-mod.wireless.network/create-wireless-net
           world-data matrix-vblock ssid password)
         :return true
         :return-on-unique-fail false
         :unique [{:label "SSID" :value-expr ssid :value-fn identity}
      {:label "matrix" :value-expr matrix-vblock
       :value-fn vb/vblock-to-string}]
         :log-create "Created network: SSID='%s'"
         :log-create-key-expr ssid
         :log-create-fail "Cannot create network: %s '%s' already exists"}
      :destroy {:log-destroy "Destroyed network: SSID='%s'"
    :log-destroy-key-expr (:ssid item)}
      :list-atom :networks
      :lookup-atom :net-lookup
      :direct-keys [:matrix :ssid]
      :key-sources [{:values-expr @(:nodes item)}]
      :log-key-fn identity
      :validator {:vblock-key :matrix
      :log-label "networks"}
      :tick {:fn my-mod.wireless.network/tick-wireless-net!}
      :nbt {:tag "networks"
      :atom :networks
      :to-nbt my-mod.wireless.network/network-to-nbt
      :from-nbt my-mod.wireless.network/network-from-nbt
      :skip? (fn [net] @(:disposed net))
      :rebuild {:lookup-atom :net-lookup
          :direct-keys [:matrix :ssid]
          :collection-keys [:nodes]}}}
     {:name node-connection
      :impl-base node-connection-impl
      :public {:create create-node-connection!
         :destroy destroy-node-connection!
         :validate validate-connections!}
      :impl {:create create-node-connection-impl!
       :destroy destroy-node-connection-impl!
       :validate validate-connections-impl!}
      :create {:args [node-vblock]
         :expr (my-mod.wireless.node-connection/create-node-conn
           world-data node-vblock)
         :return item}
      :destroy {:log-destroy "Destroyed node connection: %s"
    :log-destroy-key-expr (:node item)}
      :list-atom :connections
      :lookup-atom :node-lookup
      :direct-keys [:node]
      :collection-keys [:generators :receivers]
      :log-create "Created node connection: %s"
      :log-create-key-expr node-vblock
      :log-key-fn vb/vblock-to-string
      :validator {:vblock-key :node
      :log-label "connections"}
      :tick {:fn my-mod.wireless.node-connection/tick-node-conn!}
      :nbt {:tag "connections"
      :atom :connections
      :to-nbt my-mod.wireless.node-connection/conn-to-nbt
      :from-nbt my-mod.wireless.node-connection/conn-from-nbt
      :skip? (fn [conn] @(:disposed conn))
      :rebuild {:lookup-atom :node-lookup
          :direct-keys [:node]
          :collection-keys [:generators :receivers]}}}]
    :tick-name tick-world-data-impl!
    :nbt-write-name world-data-to-nbt-impl
    :nbt-read-name world-data-from-nbt-impl
    :nbt-after-read
    [(log/info (format "Loaded %d networks and %d connections from NBT"
           (count @(:networks world-data))
           (count @(:connections world-data))))]})
