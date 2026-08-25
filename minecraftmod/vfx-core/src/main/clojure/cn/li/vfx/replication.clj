(ns cn.li.vfx.replication
  "Authoritative VFX instance index with explicit Snapshot/Release semantics.

   Destroy is a server-authoritative tombstone. Release only removes one
   recipient-local copy, so a later tracking enter can safely receive Snapshot
   for the same stable instance id.")

(require '[clojure.set :as set]
         '[cn.li.vfx.effect-schema :as schema])

(def ^:const max-tombstones 8192)
(def ^:const tombstone-ms 30000)

(defn create-service [{:keys [catalog nearby recipients] :as options}]
  (when-not (map? catalog) (throw (ex-info "VFX replication requires a catalog" {})))
  (when-not (ifn? nearby) (throw (ex-info "VFX replication requires :nearby callback" {})))
  {:catalog catalog
   :nearby nearby
   :recipients (or recipients (fn [_ _] #{}))
   :next-id (atom 0)
   :active (atom {})
   :tombstones (atom {})})

(defn- active-instance [service instance-id]
  (or (get @(:active service) instance-id)
      (throw (ex-info "unknown VFX instance" {:instance-id instance-id}))))

(defn- packet
  ([op instance] (packet op instance (:params instance)))
  ([op instance params]
   (select-keys (assoc instance :op op :params params)
                [:op :instance-id :effect-id :owner :world-id :anchor :seed
                 :state-seq :event-seq :params :mask :snapshot-mode])))

(defn- trim-tombstones! [service]
  (let [now (System/currentTimeMillis)]
    (swap! (:tombstones service)
           (fn [tombstones]
             (let [live (into {} (filter (fn [[_ tombstone]] (> (:expires-at tombstone) now)) tombstones))]
               (if (<= (count live) max-tombstones)
                 live
                 (into {} (take-last max-tombstones (sort-by (comp :expires-at val) live)))))))))

(defn spawn! [service effect-id {:keys [owner world-id anchor seed params] :as context}]
  (let [descriptor (schema/descriptor (:catalog service) effect-id)
        id (swap! (:next-id service) inc)
        defaults (into {} (keep (fn [{:keys [name default]}] (when (some? default) [name default])) (:parameters descriptor)))
        instance {:instance-id id :effect-id effect-id :owner owner :world-id world-id :anchor anchor
                  :seed (long (or seed id)) :state-seq 0 :event-seq 0
                  :snapshot-mode (or (:snapshot-mode descriptor) :restart)
                  :params (merge defaults (or params {})) :recipients #{}}]
    (swap! (:active service) assoc id instance)
    {:handle (select-keys instance [:instance-id :effect-id :owner :world-id])
     :packet (packet :spawn instance)}))

(defn update! [service handle params]
  (let [old (active-instance service (:instance-id handle))
        descriptor (schema/descriptor (:catalog service) (:effect-id old))
        next-params (merge (:params old) params)
        mask (schema/diff-mask (:parameters descriptor) (:params old) next-params)
        changed-keys (map (comp :name #(nth (:parameters descriptor) %)) (:indices mask))
        next (assoc old :params next-params :state-seq (inc (:state-seq old))
                    :event-seq (inc (:event-seq old)) :mask mask)]
    (swap! (:active service) assoc (:instance-id old) next)
    {:handle handle :packet (packet :delta next (select-keys next-params changed-keys))}))

(defn event! [service handle event params]
  (let [old (active-instance service (:instance-id handle))
        next (assoc old :event-seq (inc (:event-seq old)) :event event :event-params (or params {}))]
    (swap! (:active service) assoc (:instance-id old) next)
    {:handle handle :packet (assoc (packet :event next {}) :event event :event-params (or params {}))}))

(defn destroy! [service handle]
  (let [instance (active-instance service (:instance-id handle))
        id (:instance-id instance)]
    (swap! (:active service) dissoc id)
    (swap! (:tombstones service) assoc id {:state-seq (:state-seq instance)
                                            :event-seq (:event-seq instance)
                                            :expires-at (+ (System/currentTimeMillis) tombstone-ms)})
    (trim-tombstones! service)
    {:handle handle :packet (packet :destroy instance)}))

(defn- visible-recipients [service instance]
  (set ((:nearby service) (:world-id instance) (:anchor instance))))

(defn tick-tracking! [service]
  (reduce (fn [packets [id instance]]
            (let [old (:recipients instance)
                  current (visible-recipients service instance)
                  entered (sort (set/difference current old))
                  exited (sort (set/difference old current))
                  next (assoc instance :recipients current)]
              (swap! (:active service) assoc id next)
              (into packets
                    (concat
                     (map (fn [recipient]
                            (assoc (packet :snapshot next) :recipient recipient :baseline? true)) entered)
                     ;; Release is recipient-local. It does not create a
                     ;; tombstone and therefore cannot block a later Snapshot.
                     (map (fn [recipient]
                            {:op :release :instance-id id :effect-id (:effect-id instance)
                             :recipient recipient :state-seq (:state-seq instance)
                             :event-seq (:event-seq instance)}) exited)))))
          [] @(:active service)))

(defn snapshot-for [service recipient]
  (->> @(:active service)
       vals
       (mapv #(assoc (packet :snapshot %) :recipient recipient :baseline? true))))

(defn tombstone [service instance-id]
  (trim-tombstones! service)
  (get @(:tombstones service) instance-id))
