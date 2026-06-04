(ns cn.li.ac.wireless.gui.container.schema-runtime
  "Schema-driven container/runtime helpers for AC GUI implementations.

  This namespace replaces cross-module usage of mcmod.gui.schema-builders,
  keeping GUI schema runtime logic inside AC where concrete container behavior
  is defined."
  (:require [cn.li.ac.wireless.gui.container.common :as common]))

(defn- resolve-state
  [tile]
  (or (common/get-tile-state tile) {}))

(defn- present-value
  [state k]
  (let [v (get state k ::missing)]
    (when (not= ::missing v)
      v)))

(defn- normalize-gui-state
  [schema state]
  (reduce
   (fn [state* field]
     (let [k (:key field)]
       (if (nil? (present-value state* k))
         (if (contains? field :default)
           (assoc state* k (:default field))
           (dissoc state* k))
         state*)))
   (or state {})
   schema))

(defn- raw-gui-value
  [field state]
  (let [state* (normalize-gui-state [field] state)
        k (:key field)]
    (cond
      (:gui-init field) ((:gui-init field) state*)
      (some? (present-value state* k)) (get state* k)
      (contains? field :default) (:default field)
      :else nil)))

(defn- coerce-gui-value
  [field value]
  (let [coerce-fn (or (:gui-coerce field) identity)]
    (if (and (nil? value) (:gui-coerce field))
      (throw (ex-info "GUI schema field requires non-nil value before coercion"
                      {:field-key (:key field)
                       :gui-container-key (:gui-container-key field (:key field))}))
      (coerce-fn value))))

(defn build-gui-atoms
  [schema state]
  (let [state* (normalize-gui-state schema state)]
    (into {}
          (for [field schema
                :when (or (:gui-sync? field) (:gui-only? field))
                :let [k (:gui-container-key field (:key field))]]
            [k (atom (coerce-gui-value field (raw-gui-value field state*)))]))))

(defn build-sync-field-mappings
  [schema]
  (into {}
        (for [field schema
              :when (:gui-sync? field)
              :let [container-key (:gui-container-key field (:key field))
                    payload-key (:gui-payload-key field (:key field))]]
          [payload-key container-key])))

(defn build-sync-to-client-fn
  [schema]
  (fn [container]
    (let [state (normalize-gui-state schema (resolve-state (:tile-entity container)))]
      (doseq [field schema
              :when (:gui-sync? field)
              :let [container-key (:gui-container-key field (:key field))
                    value (raw-gui-value field state)]]
        (when-let [atom-ref (get container container-key)]
          (let [new-val (coerce-gui-value field value)]
            (when (not= @atom-ref new-val)
              (reset! atom-ref new-val))))))))

(defn build-get-sync-data-fn
  [schema]
  (fn [container]
    (into {}
          (for [field schema
                :when (:gui-sync? field)
                :let [k (:gui-container-key field (:key field))]]
            [k (when-let [a (get container k)] @a)]))))

(defn build-apply-sync-data-fn
  [schema]
  (fn [container data]
    (doseq [field schema
            :when (:gui-sync? field)
            :let [k (:gui-container-key field (:key field))]]
      (when-let [atom-ref (get container k)]
        (when (contains? data k)
          (reset! atom-ref (coerce-gui-value field (raw-gui-value field {(:key field) (get data k)}))))))))

(defn build-on-close-fn
  [schema]
  (fn [container]
    (doseq [field schema
            :when (contains? field :gui-close-reset)
            :let [k (:gui-container-key field (:key field))
                  reset-val (:gui-close-reset field)]]
      (when-let [atom-ref (get container k)]
        (reset! atom-ref reset-val)))))
