(ns cn.li.combat.registry
  "Frozen Clojure registry for combat nodes, abilities and providers.")

(defonce ^:private state*
  (atom {:nodes {} :abilities {} :providers {} :frozen? false}))

(defn- ensure-open! []
  (when (:frozen? @state*)
    (throw (ex-info "combat registry is frozen" {}))))

(defn register-node! [{:keys [id revision run] :as descriptor}]
  (ensure-open!)
  (when-not (keyword? id) (throw (ex-info "combat node id must be a keyword" {:id id})))
  (when-not (ifn? run) (throw (ex-info "combat node requires :run" {:id id})))
  (when (contains? (:nodes @state*) id)
    (throw (ex-info "duplicate combat node" {:id id})))
  (swap! state* update :nodes assoc id (assoc descriptor :revision (long (or revision 1))))
  id)

(defn register-ability! [{:keys [id activation program] :as ability}]
  (ensure-open!)
  (when-not (keyword? id) (throw (ex-info "combat ability id must be a keyword" {:id id})))
  (when-not (contains? #{:instant :session :toggle :passive} activation)
    (throw (ex-info "combat ability has invalid activation" {:id id :activation activation})))
  (when-not (some? program) (throw (ex-info "combat ability requires :program" {:id id})))
  (when (contains? (:abilities @state*) id)
    (throw (ex-info "duplicate combat ability" {:id id})))
  (swap! state* update :abilities assoc id (assoc ability :revision (long (or (:revision ability) 1))))
  id)

(defn register-provider! [{:keys [provider-id revision nodes abilities] :as provider}]
  (ensure-open!)
  (when-not (keyword? provider-id)
    (throw (ex-info "combat provider id must be a keyword" {:provider-id provider-id})))
  (when (contains? (:providers @state*) provider-id)
    (throw (ex-info "duplicate combat provider" {:provider-id provider-id})))
  (doseq [node nodes] (register-node! (assoc node :provider-id provider-id)))
  (doseq [ability abilities] (register-ability! (assoc ability :provider-id provider-id)))
  (swap! state* update :providers assoc provider-id
         {:provider-id provider-id :revision (long (or revision 1))
          :nodes (mapv :id nodes) :abilities (mapv :id abilities)})
  provider-id)

(defn register-provider-map! [provider]
  (register-provider! provider))

(defn freeze! []
  (swap! state* assoc :frozen? true)
  (select-keys @state* [:nodes :abilities :providers]))

(defn frozen? [] (:frozen? @state*))
(defn node [id] (get-in @state* [:nodes id]))
(defn ability [id] (get-in @state* [:abilities id]))
(defn nodes [] (:nodes @state*))
(defn abilities [] (:abilities @state*))
(defn providers [] (:providers @state*))

(defn snapshot [] @state*)

(defn reset-for-test! []
  (reset! state* {:nodes {} :abilities {} :providers {} :frozen? false})
  nil)

