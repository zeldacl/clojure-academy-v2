(ns cn.li.node.contracts
  "Immutable execution contracts shared by the final combat and VFX engines.

   The values in this namespace are plain EDN.  A host adapter may attach
   opaque handles, but it must never put a loader/Minecraft object into a
   graph-visible value or network packet.")

(def ^:const schema-version 1)

(def budgets
  {:max-ir-instructions 100000
   :max-iteration 4096
   :max-scheduled-tasks 256
   :max-vfx-per-owner 128
   :max-vfx-per-world 4096
   :max-particles-per-effect 16384
   :max-client-particles 65536
   :max-normal-packet-bytes 4096
   :max-intent-bytes 64})

(defn- require-key [m k]
  (when-not (contains? m k)
    (throw (ex-info "missing execution contract field" {:field k :value m})))
  m)

(defn entry-frame
  "Immutable input sampled once at entry. Queries use this frame and may add
   explicit results, but cannot mutate it."
  [{:keys [owner world tick seed input ability-id] :as frame}]
  (doseq [k [:owner :world :tick :seed :input]] (require-key frame k))
  (when-not (integer? tick)
    (throw (ex-info "entry frame tick must be an integer" {:frame frame})))
  (when-not (integer? seed)
    (throw (ex-info "entry frame seed must be an integer" {:frame frame})))
  (cond-> {:schema-version schema-version :owner owner :world world :tick (long tick)
           :seed (long seed) :input input}
    (some? ability-id) (assoc :ability-id ability-id)))

(defn state-txn-set
  "Create a transaction set. Each owner has an independent optimistic
   revision and working state, allowing one ability to affect multiple
   players without committing one player's resource changes early."
  ([] (state-txn-set {}))
  ([owners]
   {:schema-version schema-version
    :owners (into {}
                  (map (fn [[owner {:keys [revision state]}]]
                         [owner {:base-revision (long (or revision 0))
                                 :base-state (or state {})
                                 :working-state (or state {})
                                 :patches []}])
                       owners))}))

(defn ensure-owner [txn owner revision state]
  (if (contains? (:owners txn) owner)
    txn
    (assoc-in txn [:owners owner]
              {:base-revision (long (or revision 0))
               :base-state (or state {}) :working-state (or state {}) :patches []})))

(defn owner-state [txn owner]
  (get-in txn [:owners owner :working-state]))

(defn update-owner
  [txn owner f & args]
  (when-not (contains? (:owners txn) owner)
    (throw (ex-info "state transaction owner is not registered" {:owner owner})))
  (let [path [:owners owner]
        old (get-in txn (conj path :working-state))
        next (apply f old args)]
    (-> txn
        (assoc-in (conj path :working-state) next)
        (update-in (conj path :patches) conj {:before old :after next}))))

(defn assoc-owner-in [txn owner ks value]
  (update-owner txn owner assoc-in ks value))

(defn update-owner-in [txn owner ks f & args]
  (apply update-owner txn owner update-in ks f args))

(defn txn-entries [txn]
  (mapv (fn [[owner {:keys [base-revision base-state working-state patches]}]]
          {:owner owner :base-revision base-revision :base-state base-state :state working-state :patches patches})
        (sort-by (comp pr-str key) (:owners txn))))

(defn host-command
  "A command is queued during graph evaluation and applied only after the
   complete entry passes host preflight."
  [{:keys [id capability owner world-id args] :as command}]
  (doseq [k [:id :capability :owner :world-id :args]] (require-key command k))
  (when-not (keyword? capability)
    (throw (ex-info "host command capability must be a keyword" {:command command})))
  (when-not (map? args)
    (throw (ex-info "host command args must be a map" {:command command})))
  (assoc command :schema-version schema-version
         :id id :phase :queued))

(defn outbox
  ([] {:schema-version schema-version :vfx [] :feedback [] :events [] :presentation []})
  ([outbox kind value]
   (when-not (contains? #{:vfx :feedback :events :presentation} kind)
     (throw (ex-info "unknown outbox channel" {:kind kind})))
   (update outbox kind conj value)))

(defn command-count-ok?
  [commands]
  (<= (count commands) (long (:max-ir-instructions budgets))))

(defn iteration-ok?
  [n]
  (<= (long n) (long (:max-iteration budgets))))
