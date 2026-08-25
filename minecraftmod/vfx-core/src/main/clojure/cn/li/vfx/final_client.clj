(ns cn.li.vfx.final-client
  "Client-side adapter for the final typed VFX host.

   It owns only neutral effect instances and frame batches.  Minecraft and
   renderer objects stay outside this namespace; platform code consumes the
   returned batches through the opaque VFX host ABI."
  (:require [cn.li.vfx.final-engine :as engine]))

(defn create-runtime
  [{:keys [max-instances max-batches]
    :or {max-instances 2048 max-batches 32768}}]
  {:registry (atom {})
   :instances (atom {})
   :next-id (atom 1)
   :frames (atom {})
   :latest-frame (atom nil)
   :frozen? (atom false)
   :generation (atom 0)
   :max-instances max-instances
   :max-batches max-batches})

(defn- allocate-id [runtime]
  (let [id (swap! (:next-id runtime) inc)] id))

(defn registered-effects [runtime]
  (set (keys @(:registry runtime))))
(defn effect-lifecycle [runtime effect-id]
  (get-in @(:registry runtime) [effect-id :lifecycle]))
(defn register-effect! [runtime descriptor]
  (when @(:frozen? runtime)
    (throw (ex-info "final VFX registry is frozen" {:effect-id (:id descriptor)})))
  (swap! (:registry runtime) assoc (:id descriptor) descriptor)
  nil)

(defn- new-instance [runtime effect-id owner world-id instance-key instance-id]
  (let [descriptor (get @(:registry runtime) effect-id)
        _ (when-not descriptor
            (throw (ex-info "unknown final VFX effect"
                            {:effect-id effect-id :reason :unknown-effect})))
        id (allocate-id runtime)
        state ((or (:init descriptor) (constantly {})) {})]
    {:id id :instance-id instance-id :effect-id effect-id :owner owner :world-id world-id
     :instance-key instance-key :event-seq -1
     :descriptor descriptor :state state :params {} :age 0 :events []}))

(defn ensure-instance! [runtime effect-id {:keys [owner instance-key]}]
  (or (some (fn [[id instance]]
              (when (and (= effect-id (:effect-id instance))
                         (= owner (:owner instance))
                         (= instance-key (:instance-key instance))) id)) @(:instances runtime))
      (let [instance (new-instance runtime effect-id owner nil instance-key nil)]
        (swap! (:instances runtime) assoc (:id instance) instance)
        (:id instance))))

(defn instance-for-effect [runtime effect-id]
  (some (fn [[id instance]] (when (= effect-id (:effect-id instance)) id))
        @(:instances runtime)))
(defn instance-for-owner [runtime effect-id owner]
  (some (fn [[id instance]]
          (when (and (= effect-id (:effect-id instance)) (= owner (:owner instance))) id))
        @(:instances runtime)))
(defn instance-state [runtime instance-id]
  (:state (get @(:instances runtime) instance-id)))
(defn update-instance-state! [runtime instance-id f]
  (swap! (:instances runtime) update-in [instance-id :state] f)
  nil)

(declare clear-owner!)

(defn signal! [runtime {:keys [instance]} event params]
  (swap! (:instances runtime) update-in [instance :events]
         (fnil conj []) {:event event :payload (or params {})})
  nil)

(defn- signal-instance-id [runtime effect-id owner world-id instance-key instance-id]
  (some (fn [[id instance]]
          (when (or (and instance-id (= instance-id (:instance-id instance))
                         (= effect-id (:effect-id instance))
                         (or (nil? owner) (= owner (:owner instance)))
                         (or (nil? world-id) (= world-id (:world-id instance))))
                    (and (nil? instance-id)
                         (= effect-id (:effect-id instance))
                         (= owner (:owner instance))
                         (= world-id (:world-id instance))
                         (= instance-key (:instance-key instance))))
            id))
        @(:instances runtime)))

(defn dispatch-signal!
  "Route a typed signal by stable instance-key and monotonic event-seq.

   The key is part of the final VFX ABI: effect-id/owner/world alone is not a
   unique instance because one owner may have several concurrent casts of the
   same effect. Older or duplicate packets are ignored, which makes periodic
   persistent replay safe without re-running a stale update."
  [runtime signal]
  (let [{:keys [op effect-id owner world-id instance-key instance-id event-seq event params]} signal]
    (cond
      (= :clear-owner op)
      (clear-owner! runtime owner)

      :else
      (let [_ (when-not (get @(:registry runtime) effect-id)
                (throw (ex-info "unknown final VFX effect"
                                {:effect-id effect-id :reason :unknown-effect})))
            event-seq (long (or event-seq 0))
            internal-id (or (signal-instance-id runtime effect-id owner world-id instance-key instance-id)
                            (when (contains? #{:spawn :snapshot} op)
                              (let [instance (new-instance runtime effect-id owner world-id instance-key instance-id)]
                                (swap! (:instances runtime) assoc (:id instance)
                                       (assoc instance :params (or params {})
                                              :event-seq event-seq))
                                (:id instance))))
            instance (when internal-id (get @(:instances runtime) internal-id))]
        (when (and instance (> event-seq (long (:event-seq instance))))
          (case op
            :destroy (swap! (:instances runtime) dissoc internal-id)
            (:spawn :update :trigger :snapshot)
            (do
              (swap! (:instances runtime) update internal-id
                     (fn [current]
                       (cond-> (assoc current :event-seq event-seq)
                         (contains? #{:update :snapshot :trigger} op)
                         (update :params merge (or params {})))))
              (signal! runtime {:instance internal-id} event params))
            nil))))
    nil))

(defn- tick-instance [instance context]
  (let [descriptor (:descriptor instance)
        next-state (when-let [update (:update descriptor)]
                     (update (:state instance)
                             (assoc context :instance instance :events (:events instance))))]
    (if (and (nil? next-state)
             (= :transient (:lifecycle descriptor)))
      nil
      (assoc instance :state (or next-state (:state instance)) :events []))))

(defn tick! [runtime context]
  (let [next (into {}
                   (keep (fn [[id instance]]
                           (let [updated (some-> (tick-instance instance context)
                                                 (update :age (fnil inc 0)))]
                             (when updated [id updated])))
                    @(:instances runtime)))]
    (reset! (:instances runtime) next)
    {:instances (count next)}))

(defn sample-frame! [runtime context]
  (let [batches (atom [])
        sink {:emit! #(swap! batches conj %)}]
    (doseq [[_ instance] @(:instances runtime)]
      (if-let [sample (:sample (:descriptor instance))]
        (sample (assoc context :instance instance :sink sink))
        (when (:control-graph (:descriptor instance))
          (doseq [op (engine/sample-graph (:descriptor instance)
                                          (:params instance)
                                          (:state instance)
                                          (:age instance)
                                          (long (or (:seed instance) 0)))]
            (swap! batches conj (assoc op :instance-id (:id instance)))))))
    (let [frame {:frame-id (:frame-id context) :stages (group-by :stage @batches)}]
      (swap! (:frames runtime) assoc (:frame-id frame) frame)
      (reset! (:latest-frame runtime) frame)
      frame)))

(defn frame-stage [runtime frame-id stage]
  (get-in @(:frames runtime) [frame-id :stages stage]))
(defn latest-frame-stage [runtime stage]
  (get-in @(:latest-frame runtime) [:stages stage]))
(defn release-frame! [runtime frame-id]
  (swap! (:frames runtime) dissoc frame-id)
  nil)
(defn freeze-registry! [runtime] (reset! (:frozen? runtime) true) nil)

(defn clear-owner! [runtime owner]
  (swap! (:instances runtime)
         (fn [instances]
           (into {} (remove (fn [[_ instance]] (= owner (:owner instance))) instances))))
  nil)
(defn clear-world! [runtime world-id]
  (swap! (:instances runtime)
         (fn [instances]
           (into {} (remove (fn [[_ instance]] (= world-id (:world-id instance))) instances))))
  world-id)
(defn reload-resources! [runtime generation]
  (reset! (:generation runtime) generation)
  generation)
(defn resource-generation [runtime] @(:generation runtime))
