(remove-ns 'cn.li.mc1201.runtime.accessor-registry-core)

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

(defonce accessor-registries
  {:world (atom {})
   :entity (atom {})
   :render (atom {})
   :lifecycle (atom {})})

(defonce get-registry
  (fn [domain]
    (or (get accessor-registries domain)
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
    (let [registry (get-registry domain)
          current (deref registry)]
      (when (contains? current key)
        (throw (ex-info "Accessor already registered in domain"
                        {:domain domain :key key})))
      (log/info "Registering accessor"
                {:domain domain :key key :doc (subs doc 0 (min 60 (count doc)))})
      (swap! registry assoc key {:fn accessor-fn :doc doc}))))

(defonce register-accessor-group!
  (fn [domain accessors-list]
    (doseq [[key fn doc] accessors-list]
      (register-accessor! domain key fn doc))))

(defonce get-accessor
  (fn [domain key]
    (when-let [registry (get accessor-registries domain)]
      (when-let [entry (get (deref registry) key)]
        (:fn entry)))))

(defonce get-accessor-meta
  (fn [domain key]
    (when-let [registry (get accessor-registries domain)]
      (get (deref registry) key))))

(defonce get-accessor-or-throw
  (fn [domain key]
    (or (get-accessor domain key)
        (throw (ex-info "Accessor not found"
                        {:domain domain :key key})))))

(defonce list-accessors
  (fn [domain]
    (keys (deref (get-registry domain)))))

(defonce list-accessors-detailed
  (fn [domain]
    (deref (get-registry domain))))

(defonce accessor-exists?
  (fn [domain key]
    (when-let [registry (get accessor-registries domain)]
      (contains? (deref registry) key))))

(defonce get-all-accessors
  (fn []
    (into {}
          (for [[domain registry-atom] accessor-registries]
            [domain (deref registry-atom)]))))

(defonce validate-registry-integrity
  (fn []
    (let [all-accessors (get-all-accessors)
          errors (atom [])]
      (doseq [[domain domain-accessors] all-accessors]
        (doseq [[key {:keys [fn]}] domain-accessors]
          (when-not (ifn? fn)
            (swap! errors conj {:domain domain
                                :key key
                                :error "Accessor is not callable"}))))
      (if (empty? @errors)
        {:valid true}
        {:valid false :errors @errors}))))

(defonce registry-status
  (fn []
    {:total-domains (count accessor-registries)
     :domains (into {}
                    (for [[domain registry-atom] accessor-registries]
                      [domain {:count (count (deref registry-atom))
                               :keys (keys (deref registry-atom))}]))
     :integrity (validate-registry-integrity)}))

(defonce clear-domain!
  (fn [domain]
    (reset! (get-registry domain) {})))

(defonce unregister-accessor!
  (fn [domain key]
    (swap! (get-registry domain) dissoc key)))

(defonce snapshot-registry
  (fn []
    (into {}
          (for [[domain registry-atom] accessor-registries]
            [domain (into {} (deref registry-atom))]))))
