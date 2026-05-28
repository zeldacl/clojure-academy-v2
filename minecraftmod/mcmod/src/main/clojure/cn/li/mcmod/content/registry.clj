(ns cn.li.mcmod.content.registry
  "Neutral descriptor registries used by content modules and host layers.

  This namespace stores only host-readable descriptor envelopes. Content-owned
  values remain opaque payload/metadata/handler data behind neutral field names."
  (:require [clojure.set :as set]))

(def ^:private allowed-categories
  #{:content-action
    :request
    :sync
    :player-persistence
    :client-input
    :screen
    :item-decorator
    :worldgen-feature
    :integration
    :smoke-manifest})

(def ^:private allowed-field-keys
  #{:id
    :content-id
    :message-id
    :message-key
    :nbt-key
    :format
    :payload
    :payload-key
    :operations
    :handler
    :capability-key
    :metadata
    :priority
    :tags
    :checks
    :fixtures
    :description
    :context-selector
    :result-handler
    :codec
    :factory
    :key
    :platform-key
    :host-key
    :category
    :kind
    :target
    :source
    :options
    :enabled?
    :clone?
    :order})

;; ============================================================================
;; Runtime Container
;; ============================================================================

(defn- default-content-registry-runtime-state [] {})

(defn create-content-registry-runtime
  ([] (create-content-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.content.registry/runtime ::content-registry-runtime
    :state* (or state* (atom (default-content-registry-runtime-state)))}))

(defonce ^:private installed-content-registry-runtime
  (create-content-registry-runtime))

(defn- content-registry-state-atom []
  (:state* @installed-content-registry-runtime))

(defn- content-registry-state-snapshot []
  @(content-registry-state-atom))

(defn- update-content-registry-state! [f & args]
  (apply swap! (content-registry-state-atom) f args))

(defn- normalize-category
  [category]
  (let [category-key (cond
                       (keyword? category) category
                       (string? category) (keyword category)
                       :else (throw (ex-info "Descriptor category must be a keyword or string"
                                             {:category category})))]
    (when-not (contains? allowed-categories category-key)
      (throw (ex-info "Descriptor category is not registered as a neutral host category"
                      {:category category-key
                       :allowed-categories allowed-categories})))
    category-key))

(defn- validate-descriptor-id!
  [descriptor-id]
  (when-not (or (keyword? descriptor-id)
                (and (string? descriptor-id) (not (empty? descriptor-id))))
    (throw (ex-info "Descriptor id must be a keyword or non-empty string"
                    {:descriptor-id descriptor-id}))))

(defn- validate-field-keys!
  [descriptor]
  (let [field-keys (set (keys descriptor))
        non-keyword-fields (remove keyword? field-keys)
        unknown-fields (set/difference field-keys allowed-field-keys)]
    (when (seq non-keyword-fields)
      (throw (ex-info "Descriptor field names must be keywords"
                      {:fields (vec non-keyword-fields)})))
    (when (seq unknown-fields)
      (throw (ex-info "Descriptor field names must use the neutral host envelope"
                      {:fields (vec unknown-fields)
                       :allowed-fields allowed-field-keys})))))

(defn- normalize-descriptor
  [descriptor-id descriptor]
  (when-not (map? descriptor)
    (throw (ex-info "Descriptor must be a map"
                    {:descriptor descriptor})))
  (validate-descriptor-id! descriptor-id)
  (validate-field-keys! descriptor)
  (let [descriptor (assoc descriptor :id descriptor-id)]
    (validate-field-keys! descriptor)
    descriptor))

(defn register-descriptor!
  "Register a descriptor under a neutral category.

  Re-registering the same descriptor is idempotent. Reusing an id for a
  different descriptor is rejected to keep content bootstrap repeatability safe."
  ([category descriptor]
   (register-descriptor! category (:id descriptor) descriptor))
  ([category descriptor-id descriptor]
   (let [category-key (normalize-category category)
         normalized (normalize-descriptor descriptor-id descriptor)]
     (update-content-registry-state!
            (fn [state]
              (let [existing (get-in state [category-key descriptor-id])]
                (cond
                  (nil? existing)
                  (assoc-in state [category-key descriptor-id] normalized)

                  (= existing normalized)
                  state

                  :else
                  (throw (ex-info "Descriptor id is already registered with different data"
                                  {:category category-key
                                   :descriptor-id descriptor-id}))))))
     nil)))

(defn register-descriptors!
  [category descriptors]
  (doseq [descriptor descriptors]
    (register-descriptor! category descriptor))
  nil)

(defn get-descriptor
  [category descriptor-id]
  (get-in (content-registry-state-snapshot) [(normalize-category category) descriptor-id]))

(defn require-descriptor
  [category descriptor-id]
  (or (get-descriptor category descriptor-id)
      (throw (ex-info "Descriptor is not registered"
                      {:category (normalize-category category)
                       :descriptor-id descriptor-id}))))

(defn list-descriptors
  [category]
  (->> (vals (get (content-registry-state-snapshot) (normalize-category category) {}))
       (sort-by (comp str :id))
       vec))

(defn registry-snapshot
  []
  (content-registry-state-snapshot))

(defn clear-category!
  [category]
  (update-content-registry-state! dissoc (normalize-category category))
  nil)

(defn clear-registry!
  []
  (reset! (content-registry-state-atom) (default-content-registry-runtime-state))
  nil)

(defn- invoke-descriptor-handler!
  [category descriptor-id args]
  (let [{:keys [handler]} (require-descriptor category descriptor-id)]
    (when-not (fn? handler)
      (throw (ex-info "Descriptor handler is not installed"
                      {:category (normalize-category category)
                       :descriptor-id descriptor-id})))
    (apply handler args)))

(defn register-action!
  [descriptor]
  (register-descriptor! :content-action descriptor))

(defn dispatch-action!
  [action-id context payload]
  (invoke-descriptor-handler! :content-action action-id [context payload]))

(defn register-sync-descriptor!
  [descriptor]
  (register-descriptor! :sync descriptor))

(defn list-sync-descriptors
  []
  (list-descriptors :sync))

(defn apply-sync!
  [sync-id context payload]
  (invoke-descriptor-handler! :sync sync-id [context payload]))

(defn register-player-persistence-descriptor!
  [descriptor]
  (register-descriptor! :player-persistence descriptor))

(defn list-player-persistence-descriptors
  []
  (list-descriptors :player-persistence))

(defn register-client-input-descriptor!
  [descriptor]
  (register-descriptor! :client-input descriptor))

(defn emit-client-input!
  [input-id context payload]
  (invoke-descriptor-handler! :client-input input-id [context payload]))

(defn register-smoke-manifest!
  [descriptor]
  (register-descriptor! :smoke-manifest descriptor))

(defn list-smoke-manifests
  []
  (list-descriptors :smoke-manifest))