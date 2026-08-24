(ns cn.li.vfx.replication
  "Authoritative VFX instance index and anchor-centred tracking.")
(require '[clojure.set :as set]
         '[cn.li.vfx.effect-schema :as schema])
(defn create-service [{:keys [catalog nearby recipients] :as options}]
  (when-not (map? catalog) (throw (ex-info "VFX replication requires a catalog" {})))
  (when-not (ifn? nearby) (throw (ex-info "VFX replication requires :nearby callback" {})))
  {:catalog catalog :nearby nearby :recipients (or recipients (fn [_ _] #{}))
   :next-id (atom 0) :active (atom {}) :recipient-index (atom {})})
(defn- active-instance [service instance-id]
  (or (get @(:active service) instance-id) (throw (ex-info "unknown VFX instance" {:instance-id instance-id}))))
(defn- packet [op instance]
  (select-keys (assoc instance :op op) [:op :instance-id :effect-id :owner :world-id :anchor :seed :event-seq :params :mask]))
(defn spawn! [service effect-id {:keys [owner world-id anchor seed params] :as context}]
  (let [descriptor (schema/descriptor (:catalog service) effect-id) id (swap! (:next-id service) inc) instance {:instance-id id :effect-id effect-id :owner owner :world-id world-id :anchor anchor :seed (long (or seed id)) :event-seq 0 :params (merge (into {} (map (juxt :name :default) (:parameters descriptor))) (or params {})) :recipients #{}}]
    (swap! (:active service) assoc id instance)
    {:handle (select-keys instance [:instance-id :effect-id :owner :world-id]) :packet (packet :spawn instance)}))
(defn update! [service handle params]
  (let [old (active-instance service (:instance-id handle)) descriptor (schema/descriptor (:catalog service) (:effect-id old)) next-params (merge (:params old) params) mask (schema/diff-mask (:parameters descriptor) (:params old) next-params) next (assoc old :params next-params :event-seq (inc (:event-seq old)) :mask mask)]
    (swap! (:active service) assoc (:instance-id old) next)
    {:handle handle :packet (packet :update next)}))
(defn destroy! [service handle]
  (let [instance (active-instance service (:instance-id handle))]
    (swap! (:active service) dissoc (:instance-id instance))
    {:handle handle :packet (packet :destroy instance)}))
(defn- visible-recipients [service instance]
  (set ((:nearby service) (:world-id instance) (:anchor instance))))
(defn tick-tracking! [service]
  (reduce (fn [packets [id instance]]
            (let [old (:recipients instance) current (visible-recipients service instance) entered (sort (set/difference current old)) exited (sort (set/difference old current)) next (assoc instance :recipients current)]
              (swap! (:active service) assoc id next)
              (into packets (concat (map (fn [recipient] (assoc (packet :spawn next) :recipient recipient :baseline? true)) entered) (map (fn [recipient] {:op :destroy :instance-id id :effect-id (:effect-id instance) :recipient recipient :event-seq (:event-seq instance)}) exited))))) [] @(:active service)))
(defn snapshot-for [service recipient]
  (->> @(:active service) vals (filter #(contains? (:recipients %) recipient)) (mapv #(assoc (packet :spawn %) :recipient recipient :baseline? true))))
