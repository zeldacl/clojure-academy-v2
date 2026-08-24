(ns cn.li.vfx.final-client
  "Client-side adapter for the final typed VFX host.

   It owns only neutral effect instances and frame batches.  Minecraft and
   renderer objects stay outside this namespace; platform code consumes the
   returned batches through the opaque VFX host ABI.")

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

(defn- new-instance [runtime effect-id owner world-id]
  (let [descriptor (get @(:registry runtime) effect-id)
        id (allocate-id runtime)
        state ((or (:init descriptor) (constantly {})) {})]
    {:id id :effect-id effect-id :owner owner :world-id world-id
     :descriptor descriptor :state state :events []}))

(defn ensure-instance! [runtime effect-id {:keys [owner]}]
  (or (some (fn [[id instance]]
              (when (and (= effect-id (:effect-id instance))
                         (= owner (:owner instance))) id)) @(:instances runtime))
      (let [instance (new-instance runtime effect-id owner nil)]
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

(defn dispatch-signal! [runtime signal]
  (let [{:keys [op effect-id owner world-id event params]} signal
        instance-id (or (some (fn [[id instance]]
                                (when (and (= effect-id (:effect-id instance))
                                           (= owner (:owner instance))
                                           (= world-id (:world-id instance))) id))
                              @(:instances runtime))
                        (when (= :spawn op)
                          (let [instance (new-instance runtime effect-id owner world-id)]
                            (swap! (:instances runtime) assoc (:id instance) instance)
                            (:id instance))))]
    (case op
      :destroy (when instance-id (swap! (:instances runtime) dissoc instance-id))
      (:spawn :signal :update)
      (when instance-id (signal! runtime {:instance instance-id} event params))
      :clear-owner (clear-owner! runtime owner)
      nil)
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
                           (let [updated (tick-instance instance context)]
                             (when updated [id updated])))
                    @(:instances runtime)))]
    (reset! (:instances runtime) next)
    {:instances (count next)}))

(defn sample-frame! [runtime context]
  (let [batches (atom [])
        sink {:emit! #(swap! batches conj %)}]
    (doseq [[_ instance] @(:instances runtime)]
      (when-let [sample (:sample (:descriptor instance))]
        (sample (assoc context :instance instance :sink sink))))
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
