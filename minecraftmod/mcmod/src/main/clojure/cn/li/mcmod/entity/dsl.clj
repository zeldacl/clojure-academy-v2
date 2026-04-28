(ns cn.li.mcmod.entity.dsl
  "Entity DSL - declarative registry metadata for platform adapters."
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

(defonce entity-registry (atom {}))

(defrecord EntitySpec [id registry-name entity-kind category
                       width height
                       client-tracking-range update-interval
                       fire-immune?
                       properties])

(def ^:private default-category :misc)
(def ^:private default-width 0.6)
(def ^:private default-height 0.6)
(def ^:private default-client-tracking-range 64)
(def ^:private default-update-interval 1)
(def ^:private supported-entity-kinds
  #{:scripted-projectile
    :scripted-effect
    :scripted-ray
    :scripted-marker
    :scripted-block-body})

(defn create-entity-spec
  [entity-id options]
  (map->EntitySpec
    {:id entity-id
     :registry-name (:registry-name options)
     :entity-kind (:entity-kind options)
     :category (or (:category options) default-category)
     :width (double (or (:width options) default-width))
     :height (double (or (:height options) default-height))
     :client-tracking-range (int (or (:client-tracking-range options)
                                     default-client-tracking-range))
     :update-interval (int (or (:update-interval options) default-update-interval))
     :fire-immune? (boolean (:fire-immune? options))
     :properties (or (:properties options) {})}))

(defn- validate-entity-spec
  [entity-spec]
  (when-not (string? (:id entity-spec))
    (throw (ex-info "Entity :id must be a string" {:spec entity-spec})))
  (when-not (keyword? (:entity-kind entity-spec))
    (throw (ex-info "Entity :entity-kind must be a keyword" {:spec entity-spec})))
  (when-not (contains? supported-entity-kinds (:entity-kind entity-spec))
    (throw (ex-info "Entity :entity-kind is not supported"
                    {:id (:id entity-spec)
                     :entity-kind (:entity-kind entity-spec)
                     :supported supported-entity-kinds})))
  (when (or (<= (double (:width entity-spec)) 0.0)
            (<= (double (:height entity-spec)) 0.0))
    (throw (ex-info "Entity size must be positive"
                    {:id (:id entity-spec)
                     :width (:width entity-spec)
                     :height (:height entity-spec)})))
  (let [properties (:properties entity-spec)
        kind (:entity-kind entity-spec)
        required-key (case kind
                       :scripted-projectile :projectile
                       :scripted-effect :effect
                       :scripted-ray :ray
                       :scripted-marker :marker
                       :scripted-block-body :block-body
                       nil)]
    (when (and required-key (not (map? (get properties required-key))))
      (throw (ex-info "Entity :properties is missing required kind payload"
                      {:id (:id entity-spec)
                       :entity-kind kind
                       :required required-key}))))
  true)

(defn register-entity!
  [entity-spec]
  (validate-entity-spec entity-spec)
  (log/info "Registering entity:" (:id entity-spec))
  (swap! entity-registry assoc (:id entity-spec) entity-spec)
  entity-spec)

(defn get-entity
  [entity-id]
  (get @entity-registry entity-id))

(defn list-entities
  []
  (keys @entity-registry))

(defn get-entity-registry-name
  [entity-id]
  (let [entity-spec (get-entity entity-id)
        explicit-name (:registry-name entity-spec)]
    (if (and (string? explicit-name) (not (str/blank? explicit-name)))
      explicit-name
      (str/replace (str entity-id) #"-" "_"))))

(defmacro defentity
  [entity-name & options]
  (if (map? entity-name)
    (let [options-map entity-name
          entity-id (:id options-map)]
      (when-not (string? entity-id)
        (throw (ex-info "Map-form defentity requires string :id"
                        {:form options-map})))
      `(register-entity!
         (create-entity-spec ~entity-id ~(dissoc options-map :id))))
    (let [options-map (if (and (= 1 (count options)) (map? (first options)))
                        (first options)
                        (apply hash-map options))
          entity-id (or (:id options-map) (name entity-name))]
      `(def ~entity-name
         (register-entity!
           (create-entity-spec ~entity-id ~(dissoc options-map :id)))))))
