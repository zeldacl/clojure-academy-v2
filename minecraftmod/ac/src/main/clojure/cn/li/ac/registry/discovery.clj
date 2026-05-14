(ns cn.li.ac.registry.discovery
  "Discovery layer for AC content providers.

  This is a lightweight dynamic discovery mechanism built on top of the
  existing phase-plugin registration flow. Providers register phase specs,
  and consumers materialize a merged load plan from those providers."
  (:require [cn.li.ac.registry.core :as core]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private providers* (atom {}))

(defn register-provider!
  "Register or replace a content provider by id."
  [provider]
  (let [provider* (core/normalize-provider provider)
        id (core/provider-id provider*)]
    (swap! providers* assoc id provider*)
    (log/debug "Registered content provider" id)
    provider*))

(defn unregister-provider!
  [provider-id]
  (swap! providers* dissoc provider-id)
  nil)

(defn registered-providers
  []
  (vals @providers*))

(defn discover-providers
  "Return providers in load order."
  []
  (->> (registered-providers)
       (sort-by (juxt core/priority core/provider-id))
       vec))

(defn discovered-content-phases
  "Materialize all discovered provider phases into a single ordered load plan."
  []
  (->> (discover-providers)
       (mapcat core/content-phases)
       vec))

(defn register-phase-provider!
  "Compatibility helper for code that only knows about a single phase spec."
  [phase-spec]
  (register-provider!
    {:id (:phase phase-spec)
     :priority (or (:priority phase-spec) 100)
     :phases [phase-spec]}))

(defn bootstrap-default-providers!
  "Ensure the given default phases are registered exactly once.

  Safe to call repeatedly."
  [phase-specs]
  (doseq [phase-spec phase-specs]
    (register-phase-provider! phase-spec))
  (discover-providers))
