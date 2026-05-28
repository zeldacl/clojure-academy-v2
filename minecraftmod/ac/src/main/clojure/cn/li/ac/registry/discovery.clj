(ns cn.li.ac.registry.discovery
  "Discovery layer for AC content providers.

  This is a lightweight dynamic discovery mechanism built on top of the
  existing phase-plugin registration flow. Providers register phase specs,
  and consumers materialize a merged load plan from those providers."
  (:require [cn.li.ac.registry.core :as core]
            [cn.li.mcmod.util.log :as log]))

(defn default-content-provider-registry-runtime-state
  []
  {:providers {}
   :frozen? false})

(defn create-content-provider-registry-runtime
  ([] (create-content-provider-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-content-provider-registry-runtime-state))}}]
   {::runtime ::content-provider-registry-runtime
    :state* state*}))

(defonce ^:private installed-content-provider-registry-runtime
  (create-content-provider-registry-runtime))

(defonce ^:private content-provider-registry-runtime-override* (atom nil))

(defn call-with-content-provider-registry-runtime
  [runtime f]
  (when-not (and (map? runtime)
                 (= ::content-provider-registry-runtime (::runtime runtime))
                 (some? (:state* runtime)))
    (throw (ex-info "Expected content provider registry runtime" {:runtime runtime})))
  (let [prev-override @content-provider-registry-runtime-override*]
    (try
      (reset! content-provider-registry-runtime-override* runtime)
      (f)
      (finally
        (reset! content-provider-registry-runtime-override* prev-override)))))

(defn- current-content-provider-registry-runtime
  []
  (or @content-provider-registry-runtime-override*
      @installed-content-provider-registry-runtime))

(defn- content-provider-registry-state-atom
  []
  (:state* (current-content-provider-registry-runtime)))

(defn- content-provider-registry-state-snapshot
  []
  @(content-provider-registry-state-atom))

(defn- update-content-provider-registry-state!
  [f & args]
  (apply swap! (content-provider-registry-state-atom) f args))

(defn- assert-registry-open!
  []
  (when (:frozen? (content-provider-registry-state-snapshot))
    (throw (ex-info "Content discovery provider registry is frozen" {}))))

(defn provider-registry-snapshot
  []
  (content-provider-registry-state-snapshot))

(defn reset-provider-registry-for-test!
  ([] (reset-provider-registry-for-test! {}))
  ([{:keys [providers frozen?]
     :or {providers {} frozen? false}}]
   (reset! (content-provider-registry-state-atom)
           {:providers providers
            :frozen? frozen?})
   nil))

(defn freeze-provider-registry!
  []
  (update-content-provider-registry-state! assoc :frozen? true)
  nil)

(defn register-provider!
  "Register or replace a content provider by id."
  [provider]
  (let [provider* (core/normalize-provider provider)
  id (core/provider-id* provider*)]
    (assert-registry-open!)
    (update-content-provider-registry-state! assoc-in [:providers id] provider*)
    (log/debug "Registered content provider" id)
    provider*))

(defn unregister-provider!
  [provider-id]
  (assert-registry-open!)
  (update-content-provider-registry-state! update :providers dissoc provider-id)
  nil)

(defn registered-providers
  []
  (vals (:providers (content-provider-registry-state-snapshot))))

(defn discover-providers
  "Return providers in load order."
  []
  (->> (registered-providers)
  (sort-by (juxt core/provider-priority* core/provider-id*))
       vec))

(defn discovered-content-phases
  "Materialize all discovered provider phases into a single ordered load plan."
  []
  (->> (discover-providers)
  (mapcat core/provider-phases*)
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
