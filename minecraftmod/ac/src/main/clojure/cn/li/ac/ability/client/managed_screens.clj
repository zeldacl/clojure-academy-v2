(ns cn.li.ac.ability.client.managed-screens
  "Shared runtime store for client-managed screens.

  Screen modules no longer own private runtime singletons; they store owner-keyed
  screen state in this shared runtime and require the caller to pass the owner
  explicitly for all reads/writes.")

(def ^:private default-managed-screen-runtime-state
  {:active-owners {}
   :states        {}})

(defn create-managed-screen-runtime
  []
  {::runtime ::managed-screen-runtime
   :state*   (atom default-managed-screen-runtime-state)})

(def ^:private _managed-screen-runtime (delay (create-managed-screen-runtime)))

(def ^:dynamic *managed-screen-runtime* nil)

(defn- managed-screen-runtime?
  [runtime]
  (and (map? runtime)
       (= ::managed-screen-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-managed-screen-runtime
  [runtime f]
  (when-not (managed-screen-runtime? runtime)
    (throw (ex-info "Expected managed screen runtime"
                    {:runtime runtime})))
  (binding [*managed-screen-runtime* runtime]
    (f)))

(defmacro with-managed-screen-runtime
  [runtime & body]
  `(call-with-managed-screen-runtime ~runtime (fn [] ~@body)))

(defn- current-managed-screen-runtime
  []
  (or *managed-screen-runtime*
      @_managed-screen-runtime))

(defn- require-managed-screen-runtime
  []
  (or (current-managed-screen-runtime)
      (throw (ex-info "Managed screen runtime is not bound"
                      {:required 'managed-screen-runtime}))))

(defn- managed-screen-state-atom
  []
  (:state* (require-managed-screen-runtime)))

(defn managed-screen-state-snapshot
  []
  @(managed-screen-state-atom))

(defn reset-managed-screen-state-for-test!
  []
  (reset! (managed-screen-state-atom) default-managed-screen-runtime-state)
  nil)

(defn set-active-owner!
  [screen-id owner-key]
  (swap! (managed-screen-state-atom) assoc-in [:active-owners screen-id] owner-key)
  owner-key)

(defn active-owner
  [screen-id]
  (get-in (managed-screen-state-snapshot) [:active-owners screen-id]))

(defn screen-state
  [screen-id owner-key default-state]
  (get-in (managed-screen-state-snapshot) [:states screen-id owner-key] default-state))

(defn update-screen-state!
  [screen-id owner-key default-state f & args]
  (swap! (managed-screen-state-atom)
         (fn [store]
           (apply update-in store [:states screen-id owner-key] (fnil f default-state) args))))

(defn clear-screen-state!
  [screen-id owner-key]
  (swap! (managed-screen-state-atom)
         (fn [store]
           (let [store (update-in store [:states screen-id] dissoc owner-key)]
             (if (= owner-key (get-in store [:active-owners screen-id]))
               (update store :active-owners dissoc screen-id)
               store))))
  nil)
