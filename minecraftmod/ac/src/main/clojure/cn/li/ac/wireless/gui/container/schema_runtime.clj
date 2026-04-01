(ns cn.li.ac.wireless.gui.container.schema-runtime
  "Schema-driven container/runtime helpers for AC GUI implementations.

  This namespace replaces cross-module usage of mcmod.gui.schema-builders,
  keeping GUI schema runtime logic inside AC where concrete container behavior
  is defined."
  (:require [cn.li.ac.wireless.gui.container.common :as common]))

(defn- resolve-state
  [tile]
  (or (common/get-tile-state tile) {}))

(defn build-gui-atoms
  [schema tile]
  (let [state (resolve-state tile)]
    (into {}
          (for [field schema
                :when (or (:gui-sync? field) (:gui-only? field))
                :let [k (:gui-container-key field (:key field))
                      init-fn (or (:gui-init field)
                                  (fn [s] (get s (:key field) (:default field))))]]
            [k (atom (init-fn state))]))))

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
    (let [state (resolve-state (:tile-entity container))]
      (doseq [field schema
              :when (:gui-sync? field)
              :let [container-key (:gui-container-key field (:key field))
                    state-key (:key field)
                    coerce-fn (or (:gui-coerce field) identity)
                    value (get state state-key (:default field))]]
        (when-let [atom-ref (get container container-key)]
          (let [new-val (coerce-fn value)]
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
            :let [k (:gui-container-key field (:key field))
                  coerce-fn (or (:gui-coerce field) identity)]]
      (when-let [atom-ref (get container k)]
        (when (contains? data k)
          (reset! atom-ref (coerce-fn (get data k))))))))

(defn build-on-close-fn
  [schema]
  (fn [container]
    (doseq [field schema
            :when (contains? field :gui-close-reset)
            :let [k (:gui-container-key field (:key field))
                  reset-val (:gui-close-reset field)]]
      (when-let [atom-ref (get container k)]
        (reset! atom-ref reset-val)))))
