(ns cn.li.ac.discovery.registry
  "Generic provider registry used by discovery-based bootstrap flows."
  (:require [cn.li.ac.discovery.core :as core]
            [cn.li.mcmod.util.log :as log]))

(defn default-provider-registry-runtime-state
  []
  {:providers {}
   :frozen? false})

(defn create-provider-registry-runtime
  ([] (create-provider-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-provider-registry-runtime-state))}}]
   {::runtime ::provider-registry-runtime
    :state* state*}))

(def ^:private _provider-registry-runtime (delay (create-provider-registry-runtime)))

(def ^:dynamic *provider-registry-runtime* nil)

(defn call-with-provider-registry-runtime
  [runtime f]
  (when-not (and (map? runtime)
                 (= ::provider-registry-runtime (::runtime runtime))
                 (some? (:state* runtime)))
    (throw (ex-info "Expected provider registry runtime" {:runtime runtime})))
  (binding [*provider-registry-runtime* runtime]
    (f)))

(defn- current-provider-registry-runtime
  []
  (or *provider-registry-runtime*
      @_provider-registry-runtime))

(defn- provider-registry-state-atom
  []
  (:state* (current-provider-registry-runtime)))

(defn- provider-registry-state-snapshot
  []
  @(provider-registry-state-atom))

(defn- update-provider-registry-state!
  [f & args]
  (apply swap! (provider-registry-state-atom) f args))

(defn- assert-registry-open!
  []
  (when (:frozen? (provider-registry-state-snapshot))
    (throw (ex-info "Discovery provider registry is frozen" {}))))

(defn provider-registry-snapshot
  []
  (provider-registry-state-snapshot))

(defn reset-provider-registry-for-test!
  ([] (reset-provider-registry-for-test! {}))
  ([{:keys [providers frozen?]
     :or {providers {} frozen? false}}]
   (reset! (provider-registry-state-atom)
           {:providers providers
            :frozen? frozen?})
   nil))

(defn freeze-provider-registry!
  []
  (update-provider-registry-state! assoc :frozen? true)
  nil)

(defn register-provider!
  [provider]
  (let [provider* (core/normalize-provider provider)]
    (assert-registry-open!)
    (update-provider-registry-state! assoc-in [:providers (:id provider*)] provider*)
    (log/debug "Registered discovery provider" (:id provider*))
    provider*))

(defn unregister-provider!
  [provider-id]
  (assert-registry-open!)
  (update-provider-registry-state! update :providers dissoc provider-id)
  nil)

(defn clear-providers!
  []
  (reset-provider-registry-for-test!))

(defn registered-providers
  []
  (->> (:providers (provider-registry-state-snapshot))
       vals
       (sort-by core/provider-sort-key)
       vec))
