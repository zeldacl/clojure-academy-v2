(ns cn.li.mc1201.runtime.accessor-registry-core
  "Core accessor registry implementation with schema enforcement.
   
   Accessors are registered functions/protocols that provide platform-independent
   access to Minecraft runtime data and operations. This registry:
   - Enforces accessor signature schema
   - Prevents duplicate registrations
   - Provides clear documentation for each accessor
   - Organizes accessors by domain (world, entity, render, lifecycle)"
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

(def ^:private accessor-registry-lock
  (Object.))

(def ^:dynamic *accessor-registries*
  {:world {}
   :entity {}
   :render {}
   :lifecycle {}})

(defn- accessor-registries-snapshot
  []
  (var-get #'*accessor-registries*))

(defn- update-accessor-registry!
  [domain update-fn]
  (locking accessor-registry-lock
    (let [registries (accessor-registries-snapshot)
          current (or (get registries domain)
                      (throw (ex-info "Unknown accessor domain" {:domain domain})))
          updated (update-fn current)]
      (alter-var-root #'*accessor-registries* assoc domain updated)
      updated)))

(defonce get-registry
  (fn [domain]
    (or (get (accessor-registries-snapshot) domain)
        (throw (ex-info "Unknown accessor domain" {:domain domain})))))

(defonce validate-accessor-key
  (fn [key]
    (when-not (keyword? key)
      (throw (ex-info "Accessor key must be a keyword" {:key key :type (type key)})))
    key))

(defonce validate-accessor-fn
  (fn [accessor-fn]
    (when-not (or (fn? accessor-fn)
                  (symbol? accessor-fn)
                  (var? accessor-fn)
                  (ifn? accessor-fn))
      (throw (ex-info "Accessor must be a function or callable"
                      {:value accessor-fn :type (type accessor-fn)})))
    accessor-fn))

(defonce validate-accessor-doc
  (fn [doc]
    (when-not (and (string? doc) (not (str/blank? doc)))
      (throw (ex-info "Accessor must have non-empty string documentation" {:doc doc})))
    doc))

(defonce register-accessor!
  (fn [domain key accessor-fn doc]
    (validate-accessor-key key)
    (validate-accessor-fn accessor-fn)
    (validate-accessor-doc doc)
    (log/info "Registering accessor"
              {:domain domain :key key :doc (subs doc 0 (min 60 (count doc)))})
    (update-accessor-registry!
      domain
      (fn [current]
        (when (contains? current key)
          (throw (ex-info "Accessor already registered in domain"
                          {:domain domain :key key})))
        (assoc current key {:fn accessor-fn :doc doc})))))

(defonce register-accessor-group!
  (fn [domain accessors-list]
    (doseq [[key fn doc] accessors-list]
      (register-accessor! domain key fn doc))))

(defonce get-accessor
  (fn [domain key]
    (when-let [registry (get (accessor-registries-snapshot) domain)]
      (when-let [entry (get registry key)]
        (:fn entry)))))

(defonce get-accessor-meta
  (fn [domain key]
    (when-let [registry (get (accessor-registries-snapshot) domain)]
      (get registry key))))

(defonce get-accessor-or-throw
  (fn [domain key]
    (or (get-accessor domain key)
        (throw (ex-info "Accessor not found"
                        {:domain domain :key key})))))

(defonce list-accessors
  (fn [domain]
    (keys (get-registry domain))))

(defonce list-accessors-detailed
  (fn [domain]
    (get-registry domain)))

(defonce accessor-exists?
  (fn [domain key]
    (when-let [registry (get (accessor-registries-snapshot) domain)]
      (contains? registry key))))

(defonce get-all-accessors
  (fn []
    (accessor-registries-snapshot)))

(defonce validate-registry-integrity
  (fn []
    (let [all-accessors (get-all-accessors)
          errors (transient [])]
      (doseq [[domain domain-accessors] all-accessors]
        (doseq [[key {:keys [fn]}] domain-accessors]
          (when-not (ifn? fn)
            (conj! errors {:domain domain
                           :key key
                           :error "Accessor is not callable"}))))
      (let [errors* (persistent! errors)]
        (if (empty? errors*)
        {:valid true}
          {:valid false :errors errors*})))))

(defonce registry-status
  (fn []
    {:total-domains (count (accessor-registries-snapshot))
     :domains (into {}
                    (for [[domain registry-map] (accessor-registries-snapshot)]
                      [domain {:count (count registry-map)
                               :keys (keys registry-map)}]))
     :integrity (validate-registry-integrity)}))

(defonce clear-domain!
  (fn [domain]
    (update-accessor-registry! domain (constantly {}))))

(defonce unregister-accessor!
  (fn [domain key]
    (update-accessor-registry! domain #(dissoc % key))))

(defonce snapshot-registry
  (fn []
    (into {}
          (for [[domain registry-map] (accessor-registries-snapshot)]
            [domain (into {} registry-map)]))))
